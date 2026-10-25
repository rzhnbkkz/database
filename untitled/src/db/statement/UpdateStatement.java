package db.statement;

import db.query.Condition;
import db.query.QueryEngine;

import java.util.List;
import java.util.Map;

public class UpdateStatement implements Statement {
    private String tableName;
    private Map<String, Object> values;
    private List<Condition> conditions;

    public UpdateStatement(String tableName, Map<String, Object> values, List<Condition> conditions) {
        this.tableName = tableName;
        this.values = values;
        this.conditions = conditions;
    }

    public String getTableName() {
        return tableName;
    }

    public Map<String, Object> getValues() {
        return values;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void execute(QueryEngine query) {
        int count = QueryEngine.update(query.requireTable(tableName), values, conditions);
        System.out.println(count + (count == 1 ? " row" : " rows") + " updated");
    }
}
