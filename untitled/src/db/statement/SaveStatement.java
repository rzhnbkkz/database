package db.statement;

import db.model.Table;
import db.query.QueryEngine;
import db.storage.TableSerializer;

import java.io.IOException;

public class SaveStatement implements Statement {
    private String tableName;
    private String path;

    public SaveStatement(String tableName, String path) {
        this.tableName = tableName;
        this.path = path;
    }

    public String getTableName() {
        return tableName;
    }

    public String getPath() {
        return path;
    }

    public void execute(QueryEngine query) {
        Table table = query.requireTable(tableName);
        try {
            TableSerializer.saveToFile(table, path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not save to " + path + ": " + e.getMessage());
        }
        System.out.println("Table '" + tableName + "' saved to " + path);
    }
}
