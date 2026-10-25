package db.query;

import java.util.Collections;
import java.util.List;

// Every part of a SELECT. columns empty means *; aliases has one entry per column, null where there's
// no AS; where and having are Expression.ALL_ROWS when absent; limit null means no LIMIT.
public record SelectQuery(boolean distinct, List<SelectItem> columns, List<String> aliases, TableRef from, List<Join> joins,
                          Expression where, List<SelectItem> groupBy, Expression having, List<OrderBy> orderBy,
                          Integer limit, int offset) {

    // Just FROM, JOIN, the select list (without aliases) and WHERE
    public SelectQuery(TableRef from, List<Join> joins, List<SelectItem> columns, Expression where) {
        this(false, columns, Collections.nCopies(columns.size(), null), from, joins, where, List.of(), Expression.ALL_ROWS,
                List.of(), null, 0);
    }
}
