package db.statement;

import db.query.QueryEngine;

import java.util.List;

public class CreateIndexStatement implements Statement {
    private String indexName;
    private String tableName;
    private List<String> columns;
    private boolean unique;

    public CreateIndexStatement(String indexName, String tableName, List<String> columns, boolean unique) {
        this.indexName = indexName;
        this.tableName = tableName;
        this.columns = columns;
        this.unique = unique;
    }

    public String getIndexName() {
        return indexName;
    }

    public String getTableName() {
        return tableName;
    }

    public List<String> getColumns() {
        return columns;
    }

    public boolean isUnique() {
        return unique;
    }

    public void execute(QueryEngine query) {
        query.requireTable(tableName).createIndex(indexName, columns, unique);
        System.out.println((unique ? "Unique index '" : "Index '") + indexName + "' created on " + tableName
                + " (" + String.join(", ", columns) + ")");
    }
}
