package db.statement;

import db.query.Condition;
import db.query.QueryEngine;

import java.util.List;

public class DeleteStatement implements Statement {
    private String tableName;
    private List<Condition> conditions;

    public DeleteStatement(String tableName, List<Condition> conditions) {
        this.tableName = tableName;
        this.conditions = conditions;
    }

    public String getTableName() {
        return tableName;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void execute(QueryEngine query) {
        int count = QueryEngine.delete(query.requireTable(tableName), conditions);
        System.out.println(count + (count == 1 ? " row" : " rows") + " deleted");
    }
}
