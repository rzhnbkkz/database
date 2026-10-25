package db.statement;

import db.query.Expression;
import db.query.QueryEngine;


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
        int count = QueryEngine.delete(query.requireTable(tableName), where);
        System.out.println(count + (count == 1 ? " row" : " rows") + " deleted");
    }
}
