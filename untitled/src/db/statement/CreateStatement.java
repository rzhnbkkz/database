package db.statement;

import db.model.Column;
import db.model.Table;
import db.query.QueryEngine;

import java.util.List;

public class CreateStatement implements Statement {
    private String tableName;
    private List<Column> columns;
    // IF NOT EXISTS: an existing table is left as it is instead of being an error
    private boolean ifNotExists;

    public CreateStatement(String tableName, List<Column> columns, boolean ifNotExists) {
        this.tableName = tableName;
        this.columns = columns;
        this.ifNotExists = ifNotExists;
    }

    public CreateStatement(String tableName, List<Column> columns) {
        this(tableName, columns, false);
    }

    public boolean isIfNotExists() {
        return ifNotExists;
    }

    public String getTableName() {
        return tableName;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public void execute(QueryEngine query) {
        if (query.getTable(tableName) != null) {
            if (ifNotExists) {
                System.out.println("Table '" + tableName + "' already exists, nothing created");
                return;
            }
            throw new IllegalArgumentException("Table '" + tableName + "' already exists");
        }
        query.addTable(new Table(tableName, columns));
        System.out.println("Table '" + tableName + "' created");
    }
}
