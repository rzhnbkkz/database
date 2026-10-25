package db.statement;

import db.model.Index;
import db.model.Row;
import db.query.QueryEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ShowIndexStatement implements Statement {
    private String tableName;

    public ShowIndexStatement(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() {
        return tableName;
    }

    // Printed like SELECT results, one row per index: [{name=PRIMARY, columns=id, unique=true}]
    public void execute(QueryEngine query) {
        List<Row> rows = new ArrayList<>();
        for (Index index : query.requireTable(tableName).getIndexes()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("name", index.getName());
            data.put("columns", String.join(", ", index.getColumns()));
            data.put("unique", index.isUnique());
            rows.add(new Row(data));
        }
        System.out.println(rows);
    }
}
