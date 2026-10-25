package db.statement;

import db.model.Table;
import db.query.Expression;
import db.query.QueryEngine;
import db.query.Scope;


public class DeleteStatement implements Statement {
    private String tableName;
    private Expression where;

    public DeleteStatement(String tableName, Expression where) {
        this.tableName = tableName;
        this.where = where;
    }

    public String getTableName() {
        return tableName;
    }

    public Expression getWhere() {
        return where;
    }

    public void execute(QueryEngine query) {
        Table table = query.requireTable(tableName);
        int count = QueryEngine.delete(table, where.resolve(Scope.of(table)));
        System.out.println(count + (count == 1 ? " row" : " rows") + " deleted");
    }
}
