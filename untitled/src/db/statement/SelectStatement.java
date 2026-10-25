package db.statement;

import db.query.ColumnRef;
import db.query.Expression;
import db.query.Join;
import db.query.OrderBy;
import db.query.QueryEngine;
import db.query.SelectItem;
import db.query.SelectQuery;
import db.query.TableRef;

import java.util.List;

public class SelectStatement implements Statement{
    private SelectQuery query;

    public SelectStatement(SelectQuery query) {
        this.query = query;
    }

    public SelectStatement(String tableName, Expression where) {
        this(new SelectQuery(new TableRef(tableName), List.of(), List.of(), where));
    }

    public SelectStatement(String tableName) {
        this(tableName, Expression.ALL_ROWS);
    }

    public SelectQuery getQuery() {
        return query;
    }

    // Empty means every column (SELECT *)
    public List<SelectItem> getColumns() {
        return query.columns();
    }

    public Expression getWhere() {
        return query.where();
    }

    public String getTableName() {
        return query.from().table();
    }

    public TableRef getFrom() {
        return query.from();
    }

    public List<Join> getJoins() {
        return query.joins();
    }

    public List<ColumnRef> getGroupBy() {
        return query.groupBy();
    }

    public List<OrderBy> getOrderBy() {
        return query.orderBy();
    }

    // null means no LIMIT
    public Integer getLimit() {
        return query.limit();
    }

    public int getOffset() {
        return query.offset();
    }

    public void execute(QueryEngine engine) {
        System.out.println(engine.select(query));
    }
}
