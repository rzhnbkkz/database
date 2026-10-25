package db.query;

import java.util.List;

// Every part of a SELECT. columns empty means *; limit null means no LIMIT.
public record SelectQuery(TableRef from, List<Join> joins, List<SelectItem> columns, Expression where,
                          List<ColumnRef> groupBy, List<OrderBy> orderBy, Integer limit, int offset) {

    // Just FROM, JOIN, the select list and WHERE
    public SelectQuery(TableRef from, List<Join> joins, List<SelectItem> columns, Expression where) {
        this(from, joins, columns, where, List.of(), List.of(), null, 0);
    }
}
