package db.statement;

import db.model.Column;
import db.model.Row;
import db.model.Table;
import db.query.QueryEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InsertStatement implements Statement{
    private String tableName;
    // Empty means the values are given for every column, in table order
    private List<String> columns;
    private List<Object> values;

    public InsertStatement(String tableName, List<String> columns, List<Object> values) {
        this.tableName = tableName;
        this.columns = columns;
        this.values = values;
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

    public List<Object> getValues() {
        return values;
    }

    public void execute(QueryEngine query) {
        Table table = query.requireTable(tableName);
        List<String> names = columns.isEmpty()
                ? table.getColumns().stream().map(Column::getName).toList()
                : columns;
        if (names.size() != values.size()) {
            throw new IllegalArgumentException("Expected " + names.size() + (names.size() == 1 ? " value" : " values")
                    + ", got " + values.size());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            data.put(names.get(i), values.get(i));
        }
        query.insert(table, data);
    }
}
