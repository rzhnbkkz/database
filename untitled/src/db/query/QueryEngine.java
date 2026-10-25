package db.query;

import db.model.Row;
import db.model.Table;

import java.util.*;

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

    // How one table of a SELECT is read: through an index, or a full scan when lookup is null
    private record Access(TableRef ref, Table table, IndexLookup lookup) {
        // Rows to try, before WHERE or ON; left is the combined row so far (empty for the first table)
        List<Row> rows(Row left) {
            return lookup == null ? table.getRows() : table.getRows(lookup.ids(left));
        }

        String describe(boolean first) {
            String how = lookup == null ? "full scan" : lookup.toString();
            return ref + ": " + how + (first ? "" : " for each row so far");
        }
    }

    // Everything a SELECT needs, with names checked and indexes chosen, before touching any rows
    private record Plan(Scope scope, List<Access> accesses, List<Join> joins, List<Expression> conditions,
                        Expression filter, Map<String, String> output) {}

    private Plan plan(TableRef from, List<Join> joins, List<ColumnRef> columns, Expression where) {
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
        Expression filter = where.resolve(scope);
        Map<String, String> output = new LinkedHashMap<>(); // label -> row key
        for (ColumnRef column : columns) {
            if (column.isStar()) {
                for (ColumnRef ref : scope.columns(column.table())) output.put(ref.key(), ref.key());
            } else {
                output.put(column.toString(), scope.resolve(column).key());
            }
        }

        // The first table can use WHERE: its columns are never emptied by a LEFT JOIN, so a row
        // that fails a WHERE condition on them can't appear in the result
        List<Access> accesses = new ArrayList<>();
        accesses.add(new Access(from, first, IndexLookup.find(first, from.alias(), scope.isQualified(), filter, Set.of())));
        Set<String> earlier = new HashSet<>(Set.of(from.alias()));
        for (int i = 0; i < joins.size(); i++) {
            TableRef ref = joins.get(i).table();
            accesses.add(new Access(ref, tables.get(i + 1),
                    IndexLookup.find(tables.get(i + 1), ref.alias(), true, conditions.get(i), earlier)));
            earlier.add(ref.alias());
        }
        return new Plan(scope, accesses, joins, conditions, filter, output);
    }

    // A full SELECT, with any number of joins. Rows from a single table keep plain column names;
    // joined rows are keyed alias.column. Chosen columns are labeled as written (u.name stays u.name).
    public List<Row> select(TableRef from, List<Join> joins, List<ColumnRef> columns, Expression where) {
        Plan plan = plan(from, joins, columns, where);
        Access first = plan.accesses().get(0);
        List<Row> rows = new ArrayList<>();
        for (Row row : first.rows(new Row(Map.of()))) rows.add(withAlias(row, from.alias(), plan.scope()));
        for (int i = 0; i < joins.size(); i++) {
            rows = join(rows, plan.accesses().get(i + 1), joins.get(i), plan.conditions().get(i), plan.scope());
        }

        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            if (!plan.filter().matches(row)) continue;
            if (plan.output().isEmpty()) {
                result.add(row);
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : plan.output().entrySet()) {
                Object value = row.get(entry.getValue());
                if (value != null) data.put(entry.getKey(), value);
            }
            result.add(new Row(data));
        }
        return result;
    }

    // One line per table, in the order they are read, saying whether an index is used
    public List<String> explain(TableRef from, List<Join> joins, List<ColumnRef> columns, Expression where) {
        List<Access> accesses = plan(from, joins, columns, where).accesses();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < accesses.size(); i++) lines.add(accesses.get(i).describe(i == 0));
        return lines;
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
