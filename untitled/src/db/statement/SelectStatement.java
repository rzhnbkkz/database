package db.statement;

import db.query.ColumnRef;
import db.query.Expression;
import db.query.Join;
import db.query.QueryEngine;
import db.query.TableRef;

import java.util.List;

public class SelectStatement implements Statement{
    private TableRef from;
    private List<Join> joins;
    // Empty means every column (SELECT *)
    private List<ColumnRef> columns;
    private Expression where;

    public SelectStatement(TableRef from, List<Join> joins, List<ColumnRef> columns, Expression where) {
        this.from = from;
        this.joins = joins;
        this.columns = columns;
        this.where = where;
    }

    public SelectStatement(String tableName, Expression where) {
        this(new TableRef(tableName), List.of(), List.of(), where);
    }

    public SelectStatement(String tableName) {
        this(tableName, Expression.ALL_ROWS);
    }

    public List<ColumnRef> getColumns() {
        return columns;
    }

    public Expression getWhere() {
        return where;
    }

    public String getTableName() {
        return from.table();
    }

    public TableRef getFrom() {
        return from;
    }

    public List<Join> getJoins() {
        return joins;
    }

    public void execute(QueryEngine query) {
        System.out.println(query.select(from, joins, columns, where));
    }
}
