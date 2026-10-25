package db.statement;

import db.model.Column;
import db.model.Table;
import db.query.QueryEngine;

import java.util.List;

public class CreateStatement implements Statement {
    private String tableName;
    private List<Column> columns;

    public CreateStatement(String tableName, List<Column> columns) {
        this.tableName = tableName;
        this.columns = columns;
    }

    public String getTableName() {
        return tableName;
    }

    public List<Column> getColumns() {
        return columns;
    }

    public void execute(QueryEngine query) {
        if (query.getTable(tableName) != null) {
            throw new IllegalArgumentException("Table '" + tableName + "' already exists");
        }
        query.addTable(new Table(tableName, columns));
        System.out.println("Table '" + tableName + "' created");
    }
}
