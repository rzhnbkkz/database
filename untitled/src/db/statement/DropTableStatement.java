package db.statement;

import db.query.QueryEngine;

public class DropTableStatement implements Statement {
    private String tableName;
    // IF EXISTS: a missing table is fine
    private boolean ifExists;

    public DropTableStatement(String tableName, boolean ifExists) {
        this.tableName = tableName;
        this.ifExists = ifExists;
    }

    public String getTableName() {
        return tableName;
    }

    public boolean isIfExists() {
        return ifExists;
    }

    public void execute(QueryEngine query) {
        if (query.getTable(tableName) == null && ifExists) {
            System.out.println("Table '" + tableName + "' does not exist, nothing dropped");
            return;
        }
        query.dropTable(tableName);
        System.out.println("Table '" + tableName + "' dropped");
    }
}
