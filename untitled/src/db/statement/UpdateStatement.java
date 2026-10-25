package db.statement;

import db.model.Table;
import db.query.Expression;
import db.query.QueryEngine;
import db.query.Scope;

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
        Table table = query.requireTable(tableName);
        int count = QueryEngine.update(table, values, where.resolve(Scope.of(table)));
        System.out.println(count + (count == 1 ? " row" : " rows") + " updated");
    }
}
