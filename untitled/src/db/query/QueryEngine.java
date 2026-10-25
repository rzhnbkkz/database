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
        for (Row row : table.getRows()) {
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

    // A full SELECT, with any number of joins. Rows from a single table keep plain column names;
    // joined rows are keyed alias.column. Chosen columns are labeled as written (u.name stays u.name).
    public List<Row> select(TableRef from, List<Join> joins, List<ColumnRef> columns, Expression where) {
        // Check every name before touching any rows
        Scope scope = new Scope(!joins.isEmpty());
        List<Table> tables = new ArrayList<>();
        tables.add(requireTable(from.table()));
        scope.add(from.alias(), tables.get(0));
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

        List<Row> rows = new ArrayList<>();
        for (Row row : tables.get(0).getRows()) rows.add(withAlias(row, from.alias(), scope));
        for (int i = 0; i < joins.size(); i++) {
            rows = join(rows, tables.get(i + 1), joins.get(i), conditions.get(i), scope);
        }

        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            if (!filter.matches(row)) continue;
            if (output.isEmpty()) {
                result.add(row);
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : output.entrySet()) {
                Object value = row.get(entry.getValue());
                if (value != null) data.put(entry.getKey(), value);
            }
            result.add(new Row(data));
        }
        return result;
    }

    // Nested loop join: every left row is tried against every row of the joined table
    private static List<Row> join(List<Row> leftRows, Table table, Join join, Expression on, Scope scope) {
        List<Row> result = new ArrayList<>();
        for (Row left : leftRows) {
            boolean matched = false;
            for (Row right : table.getRows()) {
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
        return table.delete(where::matches);
    }

    public static int update(Table table, Map<String, Object> values, Expression where) {
        return table.update(where::matches, values);
    }
}
