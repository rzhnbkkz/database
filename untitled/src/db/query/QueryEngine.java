package db.query;

import db.model.Change;
import db.model.Index;
import db.model.Journal;
import db.model.Row;
import db.model.Table;

import java.util.*;
import java.util.stream.Collectors;

public class QueryEngine {
    private HashMap<String, Table> tablesMap = new LinkedHashMap<>();
    // Where every change is written before it happens; nowhere for an in-memory database
    private Journal journal = Journal.NONE;
    // The open transaction: how to undo each change it made, oldest first. null outside a transaction.
    private record Undo(Table table, Table.Undo undo) {}
    private List<Undo> transaction = null;

    // What tables write their changes to. Inside a transaction, a row change first records how to undo it,
    // so ROLLBACK can put the rows back. A schema change commits the
    // transaction first, as in MySQL, so transactions only ever hold row changes. The change has passed its
    // checks but isn't applied yet, so the commit holds exactly the transaction's changes.
    private final Journal tableJournal = new Journal() {
        @Override
        public void changed(Table table, Change change) {
            if (transaction != null) {
                boolean rowChange = change instanceof Change.Insert || change instanceof Change.Update || change instanceof Change.Delete;
                // QueryEngine's commit, not this Journal's own
                if (!rowChange) QueryEngine.this.commit();
                else transaction.add(new Undo(table, table.undoFor(change)));
            }
            journal.changed(table, change);
        }
    };

    public void setJournal(Journal journal) {
        this.journal = journal;
        for (Table table : tablesMap.values()) table.setJournal(tableJournal);
    }

    public void addTable(Table table) {
        endTransaction();
        journal.created(table);
        tablesMap.put(table.getName(), table);
        table.setJournal(tableJournal);
    }

    // BEGIN. As in MySQL, BEGIN inside a transaction commits it first.
    public void begin() {
        if (inTransaction()) commit();
        journal.begin();
        transaction = new ArrayList<>();
    }

    public boolean inTransaction() {
        return transaction != null;
    }

    // COMMIT: the transaction's changes are written to the log as one record. If that fails they're rolled
    // back, so the tables never hold changes the log doesn't.
    public void commit() {
        if (!inTransaction()) throw new IllegalStateException("No transaction is open");
        try {
            journal.commit();
        } catch (RuntimeException e) {
            undo();
            throw e;
        }
        transaction = null;
    }

    // ROLLBACK: every table the transaction changed goes back to how it was at BEGIN
    public void rollback() {
        if (!inTransaction()) throw new IllegalStateException("No transaction is open");
        undo();
        journal.rollback();
    }

    // Schema changes and checkpoints commit an open transaction first, as in MySQL
    private void endTransaction() {
        if (inTransaction()) commit();
    }

    private void undo() {
        for (int i = transaction.size() - 1; i >= 0; i--) transaction.get(i).table().undo(transaction.get(i).undo());
        transaction = null;
    }

    public void checkpoint() {
        endTransaction();
        journal.checkpoint();
    }

    public Table getTable(String tableName) {
        return tablesMap.get(tableName);
    }

    public Collection<Table> getTables() {
        return Collections.unmodifiableCollection(tablesMap.values());
    }

    public Table requireTable(String tableName) {
        Table table = tablesMap.get(tableName);
        if (table == null) throw new IllegalArgumentException("Table '" + tableName + "' does not exist");
        return table;
    }

    public void dropTable(String tableName) {
        requireTable(tableName);
        endTransaction();
        journal.dropped(tableName);
        tablesMap.remove(tableName).setJournal(Journal.NONE);
    }

    public void renameTable(String from, String to) {
        Table table = requireTable(from);
        if (tablesMap.containsKey(to)) throw new IllegalArgumentException("Table '" + to + "' already exists");
        endTransaction();
        journal.renamed(from, to);
        tablesMap.remove(from);
        table.rename(to);
        tablesMap.put(to, table);
    }

    public static void insert(Table table, Map<String, Object> values) {
        Row row = new Row(values);
        table.insert(row);
    }

    public static List<Row> selectAll(Table table) {
        return new ArrayList<>(table.getRows());
    }

    // Expression.ALL_ROWS matches every row
    public static List<Row> selectWhere(Table table, Expression where) {
        List<Row> result = new ArrayList<>();
        for (Row row : candidates(table, where)) {
            if (where.matches(row)) {
                result.add(row);
            }
        }
        return result;
    }

    // Matching rows reduced to the given columns, in that order; an empty column list keeps every column
    public static List<Row> select(Table table, List<String> columns, Expression where) {
        for (String column : columns) {
            if (table.getColumns().stream().noneMatch(col -> col.getName().equals(column))) {
                throw new IllegalArgumentException("Unknown column '" + column + "'");
            }
        }
        List<Row> rows = selectWhere(table, where);
        if (columns.isEmpty()) return rows;
        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            Map<String, Object> data = new LinkedHashMap<>();
            for (String column : columns) {
                Object value = row.get(column);
                if (value != null) data.put(column, value);
            }
            result.add(new Row(data));
        }
        return result;
    }

    // How one table of a SELECT is read: through an index, or a full scan when lookup is null. For the first
    // table, ordered names an index whose order is the ORDER BY, so its rows are read in that order.
    private record Access(TableRef ref, Table table, IndexLookup lookup, Index ordered, boolean descending) {
        Access(TableRef ref, Table table, IndexLookup lookup) {
            this(ref, table, lookup, null, false);
        }

        // Rows to try, before WHERE or ON; left is the combined row so far (empty for the first table)
        List<Row> rows(Row left) {
            return lookup == null ? table.getRows() : table.getRows(lookup.ids(left));
        }

        // The first table's rows, one at a time so a LIMIT can stop early
        Iterator<Row> read() {
            if (ordered == null) return rows(new Row(Map.of())).iterator();
            Iterator<Long> ids = lookup != null ? lookup.inOrder(new Row(Map.of()), descending)
                    : ordered.inOrder(Index.Slice.ALL, descending);
            return new Iterator<>() {
                public boolean hasNext() {
                    return ids.hasNext();
                }

                public Row next() {
                    return table.getRow(ids.next());
                }
            };
        }

        String describe(boolean first) {
            String how = lookup != null ? lookup.toString()
                    : ordered != null ? "index " + ordered.getName() + " (all rows)" : "full scan";
            if (ordered != null) how += descending ? ", in reverse index order" : ", in index order";
            return ref + ": " + how + (first ? "" : " for each row so far");
        }
    }

    // Everything a SELECT needs, with names checked and indexes chosen, before touching any rows.
    // grouped is true with GROUP BY or any aggregate. aggregates are keyed by the row key their result gets.
    // orderedByIndex means the rows already come in ORDER BY order, so no sort is needed. stopAt is how many
    // matching rows are enough (OFFSET + LIMIT) when no later step needs to see them all, otherwise -1.
    private record Plan(Scope scope, List<Access> accesses, boolean orderedByIndex, long stopAt,
                        List<Expression> conditions, Expression filter,
                        boolean grouped, List<SelectItem> groupBy, Expression having, Map<String, Aggregate> aggregates,
                        List<OrderBy> order, Map<String, SelectItem> output) {}

    private Plan plan(SelectQuery query) {
        TableRef from = query.from();
        List<Join> joins = query.joins();
        Scope scope = new Scope(!joins.isEmpty(), this);
        Table first = requireTable(from.table());
        scope.add(from.alias(), first);
        List<Table> tables = new ArrayList<>(List.of(first));
        List<Expression> conditions = new ArrayList<>();
        for (Join join : joins) {
            tables.add(requireTable(join.table().table()));
            scope.add(join.table().alias(), tables.get(tables.size() - 1));
            // ON can use this table and the ones before it, not later ones
            conditions.add(join.on().resolve(scope));
        }
        Expression filter = query.where().resolve(scope);
        List<SelectItem> groupBy = query.groupBy().stream().map(item -> item.resolve(scope)).toList();
        // Kept as ALL_ROWS when absent, so "is there a HAVING?" stays a simple check
        Expression having = query.having() == Expression.ALL_ROWS ? Expression.ALL_ROWS : query.having().resolve(scope);
        // ORDER BY may name any column of the tables, not only selected ones
        List<OrderBy> order = query.orderBy().stream().map(item -> item.resolve(scope)).toList();

        Map<String, SelectItem> output = new LinkedHashMap<>(); // label -> what to show under it
        for (int i = 0; i < query.columns().size(); i++) {
            SelectItem item = query.columns().get(i);
            if (item instanceof ColumnRef column && column.isStar()) {
                for (ColumnRef ref : scope.columns(column.table())) output.put(ref.key(), ref);
                continue;
            }
            // Labeled with its alias, or as written
            output.put(query.aliases().get(i) != null ? query.aliases().get(i) : item.toString(), item.resolve(scope));
        }
        // Every aggregate the query uses, also only in HAVING or ORDER BY, is computed once per group
        List<SelectItem> used = new ArrayList<>(output.values());
        used.addAll(having.items());
        for (OrderBy item : order) used.add(item.item());
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (SelectItem item : used) {
            for (SelectItem leaf : SelectItem.leaves(item)) {
                if (leaf instanceof Aggregate aggregate) aggregates.put(aggregate.key(), aggregate);
            }
        }

        // As in MySQL's default mode: once rows are grouped, a column only has one value per group if
        // it's in GROUP BY. So anything shown, sorted on or tested in HAVING must be built from GROUP BY
        // expressions, aggregates and constants.
        boolean grouped = !groupBy.isEmpty() || !aggregates.isEmpty() || having != Expression.ALL_ROWS;
        if (grouped) {
            if (query.columns().isEmpty()) {
                throw new IllegalArgumentException("SELECT * can't be used with GROUP BY or aggregates, list the columns instead");
            }
            Set<String> groupKeys = new HashSet<>(groupBy.stream().map(SelectItem::key).toList());
            // A plain column in the select list is reported as written
            for (SelectItem item : query.columns()) {
                if (item instanceof ColumnRef column && !column.isStar() && !groupKeys.contains(scope.resolve(column).key())) {
                    throw notGrouped(column.toString());
                }
            }
            for (SelectItem item : used) checkGrouped(item, groupKeys);
        }

        // As in MySQL: once duplicates are removed, a row stands for several, which may differ in a column
        // that isn't selected, so ORDER BY can only use selected ones
        if (query.distinct()) {
            Set<String> selected = new HashSet<>(output.values().stream().map(SelectItem::key).toList());
            if (query.columns().isEmpty()) scope.columns(null).forEach(ref -> selected.add(ref.key()));
            for (OrderBy item : order) {
                if (!selected.contains(item.item().key())) {
                    throw new IllegalArgumentException("ORDER BY " + item.item() + " must be in the SELECT list when using DISTINCT");
                }
            }
        }

        // The first table can use WHERE: its columns are never emptied by a LEFT JOIN, so a row
        // that fails a WHERE condition on them can't appear in the result
        List<Access> accesses = new ArrayList<>();
        IndexLookup firstLookup = IndexLookup.find(first, from.alias(), scope.isQualified(), filter, Set.of());
        Index ordered = grouped ? null : orderingIndex(first, from.alias(), scope.isQualified(), order);
        // Reading in index order replaces a full scan, or a lookup on that same index; a lookup on another
        // index usually finds fewer rows, so then it's cheaper to keep it and sort what it finds
        if (ordered != null && firstLookup != null && firstLookup.index() != ordered) ordered = null;
        boolean descending = ordered != null && order.get(0).descending();
        accesses.add(new Access(from, first, firstLookup, ordered, descending));
        Set<String> earlier = new HashSet<>(Set.of(from.alias()));
        for (int i = 0; i < joins.size(); i++) {
            TableRef ref = joins.get(i).table();
            accesses.add(new Access(ref, tables.get(i + 1),
                    IndexLookup.find(tables.get(i + 1), ref.alias(), true, conditions.get(i), earlier)));
            earlier.add(ref.alias());
        }
        boolean orderedByIndex = ordered != null;
        // Rows come out of the joins in the first table's order, so once enough of them match, nothing
        // later can change which ones are kept
        // (with DISTINCT, duplicates found later wouldn't count, so every row is needed)
        long stopAt = !grouped && !query.distinct() && query.limit() != null && (order.isEmpty() || orderedByIndex)
                ? (long) query.offset() + query.limit() : -1;
        return new Plan(scope, accesses, orderedByIndex, stopAt, conditions, filter, grouped, groupBy, having, aggregates,
                order, output);
    }

    // An index on the first table whose columns are exactly the ORDER BY columns, all ASC or all DESC.
    // It must match exactly: with extra columns, rows that tie on ORDER BY would come out sorted by the
    // extra column instead of keeping table order. null if there is none.
    private static Index orderingIndex(Table table, String alias, boolean qualified, List<OrderBy> order) {
        if (order.isEmpty()) return null;
        List<String> columns = new ArrayList<>();
        for (OrderBy item : order) {
            if (!(item.item() instanceof ColumnRef column) || item.descending() != order.get(0).descending()) return null;
            boolean ours = qualified ? alias.equals(column.table()) : column.table() == null;
            if (!ours) return null;
            columns.add(column.column());
        }
        for (Index index : table.getIndexes()) {
            if (index.getColumns().equals(columns)) return index;
        }
        return null;
    }

    // A GROUP BY expression, an aggregate or a constant is fine; otherwise every part must be
    private static void checkGrouped(SelectItem item, Set<String> groupKeys) {
        if (groupKeys.contains(item.key()) || item instanceof Aggregate) return;
        if (item instanceof ColumnRef column) throw notGrouped(column.key());
        for (SelectItem child : item.children()) checkGrouped(child, groupKeys);
    }

    private static IllegalArgumentException notGrouped(String column) {
        return new IllegalArgumentException("Column '" + column + "' must be in GROUP BY, or inside an aggregate like COUNT or MAX");
    }

    // A full SELECT. Rows from a single table keep plain column names; joined rows are keyed
    // alias.column. Chosen columns are labeled as written (u.name stays u.name, sum(total) is SUM(total)).
    // Steps: join, WHERE, group, HAVING, sort, LIMIT/OFFSET, and only then pick the columns, so ORDER BY
    // can use a column that isn't selected.
    public List<Row> select(SelectQuery query) {
        return run(query).rows();
    }

    // A SELECT's rows with its column labels in order, so empty values can be shown too
    public record Result(List<String> columns, List<Row> rows) {}

    public Result run(SelectQuery query) {
        Plan plan = plan(query);
        List<String> columns = plan.output().isEmpty()
                ? plan.scope().columns(null).stream().map(ColumnRef::key).toList()
                : List.copyOf(plan.output().keySet());
        return new Result(columns, rows(query, plan));
    }

    private List<Row> rows(SelectQuery query, Plan plan) {
        // One first-table row at a time through the joins and WHERE, so a LIMIT can stop the reading early
        List<Row> matching = new ArrayList<>();
        Iterator<Row> firstRows = plan.accesses().get(0).read();
        while (firstRows.hasNext() && (plan.stopAt() < 0 || matching.size() < plan.stopAt())) {
            List<Row> rows = List.of(withAlias(firstRows.next(), query.from().alias(), plan.scope()));
            for (int i = 0; i < query.joins().size(); i++) {
                rows = join(rows, plan.accesses().get(i + 1), query.joins().get(i), plan.conditions().get(i), plan.scope());
            }
            for (Row row : rows) {
                if (plan.filter().matches(row)) matching.add(row);
            }
        }
        if (plan.grouped()) {
            matching = group(matching, plan);
            matching.removeIf(row -> !plan.having().matches(row));
        }
        // List.sort is stable, so rows that tie keep their table order
        if (!plan.order().isEmpty() && !plan.orderedByIndex()) matching.sort(comparator(plan.order()));
        // DISTINCT compares the chosen columns, so they're picked first; otherwise only the kept rows are
        if (query.distinct()) {
            Set<Map<String, Object>> seen = new HashSet<>();
            List<Row> unique = new ArrayList<>();
            for (Row row : matching) {
                Row chosen = choose(row, plan);
                // The first of equal rows is kept, so the sort order stays
                if (seen.add(chosen.getData())) unique.add(chosen);
            }
            return cut(unique, query);
        }
        List<Row> result = new ArrayList<>();
        for (Row row : cut(matching, query)) result.add(choose(row, plan));
        return result;
    }

    private static List<Row> cut(List<Row> rows, SelectQuery query) {
        int start = Math.min(query.offset(), rows.size());
        int end = query.limit() == null ? rows.size() : (int) Math.min(rows.size(), (long) start + query.limit());
        return rows.subList(start, end);
    }

    // The row reduced to the chosen columns and expressions, under their labels; empty values are left out
    private static Row choose(Row row, Plan plan) {
        if (plan.output().isEmpty()) return row;
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, SelectItem> entry : plan.output().entrySet()) {
            Object value = entry.getValue().evaluate(row);
            if (value != null) data.put(entry.getKey(), value);
        }
        return new Row(data);
    }

    // One row per group, in order of first appearance, holding the GROUP BY values and each aggregate's
    // result. Empty values form a group of their own. Without GROUP BY every row is in one group, so
    // aggregates over no rows still give one row (COUNT(*) = 0). Each GROUP BY value is stored under its
    // expression's key, also when empty, so UPPER(city) in the select list finds its group's value.
    private static List<Row> group(List<Row> rows, Plan plan) {
        Map<List<Object>, List<Row>> groups = new LinkedHashMap<>();
        if (plan.groupBy().isEmpty()) {
            groups.put(List.of(), rows);
        } else {
            for (Row row : rows) {
                List<Object> key = new ArrayList<>();
                for (SelectItem item : plan.groupBy()) key.add(item.evaluate(row));
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        List<Row> result = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Row>> group : groups.entrySet()) {
            Map<String, Object> data = new LinkedHashMap<>();
            for (int i = 0; i < plan.groupBy().size(); i++) {
                data.put(plan.groupBy().get(i).key(), group.getKey().get(i));
            }
            for (Map.Entry<String, Aggregate> aggregate : plan.aggregates().entrySet()) {
                Object value = aggregate.getValue().compute(group.getValue());
                if (value != null) data.put(aggregate.getKey(), value);
            }
            result.add(new Row(data));
        }
        return result;
    }

    // One line per table, in the order they are read, saying whether an index is used,
    // then the grouping, sort and limit if there are any
    public List<String> explain(SelectQuery query) {
        Plan plan = plan(query);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < plan.accesses().size(); i++) lines.add(plan.accesses().get(i).describe(i == 0));
        if (!plan.groupBy().isEmpty()) {
            lines.add("group by " + plan.groupBy().stream().map(SelectItem::toString).collect(Collectors.joining(", ")));
        } else if (plan.grouped()) {
            lines.add("aggregate all rows into one");
        }
        if (plan.having() != Expression.ALL_ROWS) lines.add("having " + plan.having());
        if (!plan.order().isEmpty() && !plan.orderedByIndex()) {
            lines.add("sort by " + plan.order().stream().map(OrderBy::toString).collect(Collectors.joining(", ")));
        }
        if (query.distinct()) lines.add("remove duplicate rows");
        List<String> cut = new ArrayList<>();
        if (query.limit() != null) cut.add("limit " + query.limit());
        if (query.offset() > 0) cut.add("offset " + query.offset());
        if (!cut.isEmpty()) lines.add(String.join(" ", cut) + (plan.stopAt() >= 0 ? ", stops reading early" : ""));
        return lines;
    }

    private static Comparator<Row> comparator(List<OrderBy> order) {
        Comparator<Row> result = null;
        for (OrderBy item : order) {
            SelectItem sortBy = item.item();
            Comparator<Row> byColumn = (a, b) -> compareForSort(sortBy.evaluate(a), sortBy.evaluate(b));
            if (item.descending()) byColumn = byColumn.reversed();
            result = result == null ? byColumn : result.thenComparing(byColumn);
        }
        return result;
    }

    // Values compare like in WHERE. Empty cells come first, as in MySQL, so last with DESC.
    private static int compareForSort(Object a, Object b) {
        if (a == null || b == null) return a == null ? (b == null ? 0 : -1) : 1;
        Integer cmp = Condition.compare(a, b);
        // Can't happen within one typed column; keep the order rather than fail
        return cmp == null ? 0 : cmp;
    }

    // Each left row is tried against the joined table's rows: all of them, or only the ones its index finds
    private static List<Row> join(List<Row> leftRows, Access access, Join join, Expression on, Scope scope) {
        List<Row> result = new ArrayList<>();
        for (Row left : leftRows) {
            boolean matched = false;
            for (Row right : access.rows(left)) {
                Map<String, Object> data = new LinkedHashMap<>(left.getData());
                data.putAll(withAlias(right, join.table().alias(), scope).getData());
                Row combined = new Row(data);
                if (on.matches(combined)) {
                    result.add(combined);
                    matched = true;
                }
            }
            // The joined table's columns are simply absent, which reads as empty
            if (!matched && join.type() == Join.Type.LEFT) result.add(left);
        }
        return result;
    }

    // In a join, name -> alias.name, so columns of different tables can't collide
    private static Row withAlias(Row row, String alias, Scope scope) {
        if (!scope.isQualified()) return row;
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.getData().entrySet()) {
            data.put(new ColumnRef(alias, entry.getKey()).key(), entry.getValue());
        }
        return new Row(data);
    }

    public static List<Row> selectWhere(Table table, String column, Object value) {
        return selectWhere(table, new Condition(column, value));
    }

    public static int delete(Table table, Expression where) {
        return table.delete(candidateIds(table, where), where::matches);
    }

    public static int update(Table table, Map<String, Object> values, Expression where) {
        return table.update(candidateIds(table, where), where::matches, values);
    }

    // SET values worked out for each row, like n = n + 1
    public static int update(Table table, java.util.function.Function<Row, Map<String, Object>> valuesFor, Expression where) {
        return table.update(candidateIds(table, where), where::matches, valuesFor);
    }

    // For a single table: ids an index narrows the WHERE down to, or null to check every row
    private static Collection<Long> candidateIds(Table table, Expression where) {
        IndexLookup lookup = IndexLookup.find(table, table.getName(), false, where, Set.of());
        return lookup == null ? null : lookup.ids(new Row(Map.of()));
    }

    private static List<Row> candidates(Table table, Expression where) {
        Collection<Long> ids = candidateIds(table, where);
        return ids == null ? table.getRows() : table.getRows(ids);
    }
}
