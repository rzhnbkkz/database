package db.statement;

import db.model.Column;
import db.model.Row;
import db.model.Table;
import db.query.QueryEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class InsertStatement implements Statement{
    private String tableName;
    // Empty means the values are given for every column, in table order
    private List<String> columns;
    // One list of values per row; a value may be Table.DEFAULT
    private List<List<Object>> rows;

    private InsertStatement(String tableName, List<String> columns) {
        this.tableName = tableName;
        this.columns = columns;
    }

    // INSERT ... VALUES (...), (...)
    public static InsertStatement ofRows(String tableName, List<String> columns, List<List<Object>> rows) {
        InsertStatement statement = new InsertStatement(tableName, columns);
        statement.rows = rows;
        return statement;
    }

    public InsertStatement(String tableName, List<String> columns, List<Object> values) {
        this(tableName, columns);
        this.rows = List.of(values);
    }

    public InsertStatement(String tableName, Row row) {
        this(tableName, new ArrayList<>(row.getData().keySet()), new ArrayList<>(row.getData().values()));
    }

    public String getTableName() {
        return tableName;
    }

    public List<String> getColumns() {
        return columns;
    }

    // The first row's values
    public List<Object> getValues() {
        return rows.get(0);
    }

    public List<List<Object>> getRows() {
        return rows;
    }

    // All rows go in, or none do
    public void execute(QueryEngine query) {
        Table table = query.requireTable(tableName);
        List<String> names = columns.isEmpty()
                ? table.getColumns().stream().map(Column::getName).toList()
                : columns;
        List<Map<String, Object>> newRows = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<Object> values = rows.get(r);
            if (names.size() != values.size()) {
                throw new IllegalArgumentException("Expected " + names.size() + (names.size() == 1 ? " value" : " values")
                        + ", got " + values.size() + (rows.size() > 1 ? " in row " + (r + 1) : ""));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                // DEFAULT is the same as leaving the column out
                if (values.get(i) != Table.DEFAULT) data.put(names.get(i), values.get(i));
            }
            newRows.add(data);
        }
        List<Integer> generated = table.insertAll(newRows);
        String message = newRows.size() + (newRows.size() == 1 ? " row" : " rows") + " inserted";
        if (!generated.isEmpty()) {
            message += " (" + table.getPrimaryKey().getName() + " = "
                    + generated.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ")";
        }
        System.out.println(message);
    }
}
