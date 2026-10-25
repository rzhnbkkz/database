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

    // An empty condition list matches every row
    public static List<Row> selectWhere(Table table, List<Condition> conditions) {
        List<Row> result = new ArrayList<>();
        for (Row row : table.getRows()) {
            if (Condition.matchesAll(conditions, row)) {
                result.add(row);
            }
        }
        return result;
    }

    // Matching rows reduced to the given columns, in that order; an empty column list keeps every column
    public static List<Row> select(Table table, List<String> columns, List<Condition> conditions) {
        for (String column : columns) {
            if (table.getColumns().stream().noneMatch(col -> col.getName().equals(column))) {
                throw new IllegalArgumentException("Unknown column '" + column + "'");
            }
        }
        List<Row> rows = selectWhere(table, conditions);
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

    public static List<Row> selectWhere(Table table, String column, Object value) {
        return selectWhere(table, List.of(new Condition(column, value)));
    }

    public static int delete(Table table, List<Condition> conditions) {
        return table.delete(row -> Condition.matchesAll(conditions, row));
    }

    public static int update(Table table, Map<String, Object> values, List<Condition> conditions) {
        return table.update(row -> Condition.matchesAll(conditions, row), values);
    }
}
