package db.query;

import db.model.Index;
import db.model.Row;
import db.model.Table;

import java.util.*;
import java.util.stream.Collectors;

public class QueryEngine {
    private HashMap<String, Table> tablesMap = new LinkedHashMap<>();

    public void addTable(Table table) {
        tablesMap.put(table.getName(), table);
    }

    public Table getTable(String tableName) {
        return tablesMap.get(tableName);
    }

    public Table requireTable(String tableName) {
        Table table = tablesMap.get(tableName);
        if (table == null) throw new IllegalArgumentException("Table '" + tableName + "' does not exist");
        return table;
    }

    public void dropTable(String tableName) {
        requireTable(tableName);
        tablesMap.remove(tableName);
    }

    public void renameTable(String from, String to) {
        Table table = requireTable(from);
        if (tablesMap.containsKey(to)) throw new IllegalArgumentException("Table '" + to + "' already exists");
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
                        boolean grouped, List<ColumnRef> groupBy, Expression having, Map<String, Aggregate> aggregates,
                        List<OrderBy> order, Map<String, String> output) {}

    private Plan plan(SelectQuery query) {
        TableRef from = query.from();
        List<Join> joins = query.joins();
        Scope scope = new Scope(!joins.isEmpty());
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
        List<ColumnRef> groupBy = query.groupBy().stream().map(scope::resolve).toList();
        // Kept as ALL_ROWS when absent, so "is there a HAVING?" stays a simple check
        Expression having = query.having() == Expression.ALL_ROWS ? Expression.ALL_ROWS : query.having().resolve(scope);
        // ORDER BY may name any column of the tables, not only selected ones
        List<OrderBy> order = query.orderBy().stream().map(item -> item.resolve(scope)).toList();

        Map<String, String> output = new LinkedHashMap<>(); // label -> row key
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        for (int i = 0; i < query.columns().size(); i++) {
            SelectItem item = query.columns().get(i);
            // Labeled with its alias, or as written
            String label = query.aliases().get(i) != null ? query.aliases().get(i) : item.toString();
            if (item instanceof Aggregate aggregate) {
                Aggregate resolved = aggregate.resolve(scope);
                aggregates.put(resolved.key(), resolved);
                output.put(label, resolved.key());
            } else if (((ColumnRef) item).isStar()) {
                for (ColumnRef ref : scope.columns(((ColumnRef) item).table())) output.put(ref.key(), ref.key());
            } else {
                output.put(label, scope.resolve((ColumnRef) item).key());
            }
        }
        // Aggregates used only by HAVING or ORDER BY are computed too
        List<SelectItem> used = new ArrayList<>(having.items());
        for (OrderBy item : order) used.add(item.item());
        for (SelectItem item : used) {
            if (item instanceof Aggregate aggregate) aggregates.put(aggregate.key(), aggregate);
        }

        // As in MySQL's default mode: once rows are grouped, a plain column only has one value per
        // group if it's one of the GROUP BY columns
        boolean grouped = !groupBy.isEmpty() || !aggregates.isEmpty() || having != Expression.ALL_ROWS;
        if (grouped) {
            if (query.columns().isEmpty()) {
                throw new IllegalArgumentException("SELECT * can't be used with GROUP BY or aggregates, list the columns instead");
            }
            Set<String> groupKeys = new HashSet<>(groupBy.stream().map(ColumnRef::key).toList());
            for (SelectItem item : query.columns()) {
                if (!(item instanceof ColumnRef column)) continue;
                List<ColumnRef> named = column.isStar() ? scope.columns(column.table()) : List.of(scope.resolve(column));
                for (ColumnRef ref : named) {
                    if (!groupKeys.contains(ref.key())) throw notGrouped(column.isStar() ? ref.key() : column.toString());
                }
            }
            for (SelectItem item : used) {
                if (item instanceof ColumnRef column && !groupKeys.contains(column.key())) throw notGrouped(column.key());
            }
        }

        // As in MySQL: once duplicates are removed, a row stands for several, which may differ in a column
        // that isn't selected, so ORDER BY can only use selected ones
        if (query.distinct()) {
            Set<String> selected = new HashSet<>(output.values());
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

    private static IllegalArgumentException notGrouped(String column) {
        return new IllegalArgumentException("Column '" + column + "' must be in GROUP BY, or inside an aggregate like COUNT or MAX");
    }

    // A full SELECT. Rows from a single table keep plain column names; joined rows are keyed
    // alias.column. Chosen columns are labeled as written (u.name stays u.name, sum(total) is SUM(total)).
    // Steps: join, WHERE, group, HAVING, sort, LIMIT/OFFSET, and only then pick the columns, so ORDER BY
    // can use a column that isn't selected.
    public List<Row> select(SelectQuery query) {
        Plan plan = plan(query);
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

    // The row reduced to the chosen columns, under their labels; empty cells are left out
    private static Row choose(Row row, Plan plan) {
        if (plan.output().isEmpty()) return row;
        Map<String, Object> data = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : plan.output().entrySet()) {
            Object value = row.get(entry.getValue());
            if (value != null) data.put(entry.getKey(), value);
        }
        return new Row(data);
    }

    // One row per group, in order of first appearance, holding the GROUP BY values and each aggregate's
    // result. Empty cells form a group of their own. Without GROUP BY every row is in one group, so
    // aggregates over no rows still give one row (COUNT(*) = 0).
    private static List<Row> group(List<Row> rows, Plan plan) {
        Map<List<Object>, List<Row>> groups = new LinkedHashMap<>();
        if (plan.groupBy().isEmpty()) {
            groups.put(List.of(), rows);
        } else {
            for (Row row : rows) {
                List<Object> key = new ArrayList<>();
                for (ColumnRef column : plan.groupBy()) key.add(row.get(column.key()));
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
            }
        }
        List<Row> result = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Row>> group : groups.entrySet()) {
            Map<String, Object> data = new LinkedHashMap<>();
            for (int i = 0; i < plan.groupBy().size(); i++) {
                Object value = group.getKey().get(i);
                if (value != null) data.put(plan.groupBy().get(i).key(), value);
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
            lines.add("group by " + plan.groupBy().stream().map(ColumnRef::toString).collect(Collectors.joining(", ")));
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
            // An aggregate's result is stored under its name, like COUNT(*)
            String key = item.item().key();
            Comparator<Row> byColumn = (a, b) -> compareForSort(a.get(key), b.get(key));
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
