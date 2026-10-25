package db.statement;

import db.query.Expression;
import db.query.QueryEngine;

import java.util.Map;

public class UpdateStatement implements Statement {
    private String tableName;
    private Map<String, Object> values;
    private Expression where;

    public UpdateStatement(String tableName, Map<String, Object> values, Expression where) {
        this.tableName = tableName;
        this.values = values;
        this.where = where;
    }

    public String getTableName() {
        return tableName;
    }

    public Map<String, Object> getValues() {
        return values;
    }

    public Expression getWhere() {
        return where;
    }

    public void execute(QueryEngine query) {
        int count = QueryEngine.update(query.requireTable(tableName), values, where);
        System.out.println(count + (count == 1 ? " row" : " rows") + " updated");
    }
}
