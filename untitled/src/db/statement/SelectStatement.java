package db.statement;

import db.query.Expression;
import db.query.QueryEngine;

import java.util.List;

public class SelectStatement implements Statement{
    private String tableName;
    private List<String> columns;
    private Expression where;

    public SelectStatement(String tableName, List<String> columns, Expression where) {
        this.tableName = tableName;
        this.columns = columns;
        this.where = where;
    }

    public SelectStatement(String tableName, Expression where) {
        this(tableName, List.of(), where);
    }

    public SelectStatement(String tableName) {
        this(tableName, Expression.ALL_ROWS);
    }

    public List<String> getColumns() {
        return columns;
    }

    public Expression getWhere() {
        return where;
    }

    public String getTableName() {
        return tableName;
    }

    public void execute(QueryEngine query) {
        System.out.println(QueryEngine.select(query.requireTable(tableName), columns, where));
    }
}
