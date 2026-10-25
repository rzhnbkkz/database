package db.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

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

    // The same query rebuilt with its columns and subqueries passed through the rewriter
    public SelectQuery rewrite(Rewriter rewriter) {
        List<SelectItem> newColumns = columns.stream()
                .map(item -> item instanceof ColumnRef column && column.isStar() ? item : item.rewrite(rewriter)).toList();
        List<Join> newJoins = joins.stream().map(join -> new Join(join.type(), join.table(), join.on().rewrite(rewriter))).toList();
        List<SelectItem> newGroupBy = groupBy.stream().map(item -> item.rewrite(rewriter)).toList();
        List<OrderBy> newOrderBy = orderBy.stream().map(item -> new OrderBy(item.item().rewrite(rewriter), item.descending())).toList();
        return new SelectQuery(distinct, newColumns, aliases, from, newJoins, rewrite(where, rewriter), newGroupBy,
                rewrite(having, rewriter), newOrderBy, limit, offset);
    }

    // ALL_ROWS stays itself, since the planner tells "no HAVING" by it
    private static Expression rewrite(Expression expression, Rewriter rewriter) {
        return expression == Expression.ALL_ROWS ? expression : expression.rewrite(rewriter);
    }

    // The same query with a LIMIT, when it has none yet
    SelectQuery withLimit(int newLimit) {
        if (limit != null || offset != 0) return this;
        return new SelectQuery(distinct, columns, aliases, from, joins, where, groupBy, having, orderBy, newLimit, 0);
    }

    // As SQL, for labels and messages
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SELECT ");
        if (distinct) sb.append("DISTINCT ");
        if (columns.isEmpty()) {
            sb.append("*");
        } else {
            List<String> items = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                items.add(columns.get(i) + (aliases.get(i) == null ? "" : " AS " + aliases.get(i)));
            }
            sb.append(String.join(", ", items));
        }
        sb.append(" FROM ").append(from);
        for (Join join : joins) {
            sb.append(join.type() == Join.Type.LEFT ? " LEFT JOIN " : " JOIN ").append(join.table()).append(" ON ").append(join.on());
        }
        if (where != Expression.ALL_ROWS) sb.append(" WHERE ").append(where);
        if (!groupBy.isEmpty()) sb.append(" GROUP BY ").append(groupBy.stream().map(Object::toString).collect(Collectors.joining(", ")));
        if (having != Expression.ALL_ROWS) sb.append(" HAVING ").append(having);
        if (!orderBy.isEmpty()) sb.append(" ORDER BY ").append(orderBy.stream().map(Object::toString).collect(Collectors.joining(", ")));
        if (limit != null) sb.append(" LIMIT ").append(limit);
        if (offset > 0) sb.append(" OFFSET ").append(offset);
        return sb.toString();
    }
}
