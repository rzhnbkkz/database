package db.statement;

import db.query.QueryEngine;

public class DropIndexStatement implements Statement {
    private String indexName;
    private String tableName;

    public DropIndexStatement(String indexName, String tableName) {
        this.indexName = indexName;
        this.tableName = tableName;
    }

    public String getIndexName() {
        return indexName;
    }

    public String getTableName() {
        return tableName;
    }

    public void execute(QueryEngine query) {
        query.requireTable(tableName).dropIndex(indexName);
        System.out.println("Index '" + indexName + "' dropped");
    }
}
