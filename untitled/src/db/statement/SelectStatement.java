package db.statement;

import db.query.Condition;
import db.query.QueryEngine;

import java.util.List;

public class SelectStatement implements Statement{
    private String tableName;
    private List<String> columns;
    private List<Condition> conditions;

    public SelectStatement(String tableName, List<String> columns, List<Condition> conditions) {
        this.tableName = tableName;
        this.columns = columns;
        this.conditions = conditions;
    }

    public SelectStatement(String tableName, List<Condition> conditions) {
        this(tableName, List.of(), conditions);
    }

    public SelectStatement(String tableName) {
        this(tableName, List.of());
    }

    public List<String> getColumns() {
        return columns;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public String getTableName() {
        return tableName;
    }

    public void execute(QueryEngine query) {
        System.out.println(QueryEngine.select(query.requireTable(tableName), columns, conditions));
    }
}
