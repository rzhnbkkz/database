package db.statement;

import db.query.QueryEngine;

public class CreateIndexStatement implements Statement {
    private String indexName;
    private String tableName;
    private String column;

    public CreateIndexStatement(String indexName, String tableName, String column) {
        this.indexName = indexName;
        this.tableName = tableName;
        this.column = column;
    }

    public String getIndexName() {
        return indexName;
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumn() {
        return column;
    }

    public void execute(QueryEngine query) {
        query.requireTable(tableName).createIndex(indexName, column);
        System.out.println("Index '" + indexName + "' created on " + tableName + " (" + column + ")");
    }
}
