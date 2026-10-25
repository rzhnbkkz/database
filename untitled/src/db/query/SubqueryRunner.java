package db.query;

import db.model.Row;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

// Runs a subquery for the query around it. A name the subquery's own tables don't have (at any depth of
// subqueries inside it) is looked up in the outer query, as in SQL: in
//   SELECT name FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)
// u.id is the outer row's. For each outer row, those outer columns are swapped for the row's values and
// the result runs as an ordinary query, so o.user_id = 5 can use an index. A subquery without outer
// columns gives the same rows every time, so it runs once.
final class SubqueryRunner {
    private final SelectQuery query;
    private final Scope outer;
    // The outer columns it uses, resolved in the outer scope (their keys are the outer row's keys)
    private final List<ColumnRef> outerColumns = new ArrayList<>();
    private List<Row> cachedRows;
    private ValueSet cachedValues;

    // Checks every name now, so a wrong one is reported even if no outer row ever runs the subquery
    SubqueryRunner(SelectQuery query, Scope outer) {
        this.query = query;
        this.outer = outer;
        bind(column -> {
            if (!outerColumns.contains(column)) outerColumns.add(column);
            return column;
        });
    }

    List<ColumnRef> outerColumns() {
        return outerColumns;
    }

    // The subquery's result rows for one outer row
    List<Row> rows(Row outerRow) {
        if (outerColumns.isEmpty()) {
            if (cachedRows == null) cachedRows = run(query);
            return cachedRows;
        }
        return run(bind(column -> new Literal(outerRow.get(column.key()))));
    }

    // Whether it gives any row; it stops at the first one found
    boolean exists(Row outerRow) {
        if (outerColumns.isEmpty()) return !rows(outerRow).isEmpty();
        return !run(bind(column -> new Literal(outerRow.get(column.key()))).withLimit(1)).isEmpty();
    }

    // A one-column subquery's values, for IN
    ValueSet values(Row outerRow) {
        if (outerColumns.isEmpty() && cachedValues != null) return cachedValues;
        ValueSet values = new ValueSet(rows(outerRow).stream().map(SubqueryRunner::single).toList());
        if (outerColumns.isEmpty()) cachedValues = values;
        return values;
    }

    // A one-column subquery's single value, for (SELECT ...) used as a value: empty when it gives no rows,
    // and an error when it gives more than one, as in MySQL
    Object value(Row outerRow) {
        List<Row> rows = rows(outerRow);
        if (rows.size() > 1) throw new IllegalArgumentException("Subquery returned " + rows.size() + " rows, expected at most 1: (" + query + ")");
        return rows.isEmpty() ? null : single(rows.get(0));
    }

    // The value of a row with one selected column; empty values aren't stored in rows, so then null
    private static Object single(Row row) {
        return row.getData().isEmpty() ? null : row.getData().values().iterator().next();
    }

    private List<Row> run(SelectQuery bound) {
        return outer.engine().select(bound);
    }

    // The query with each outer column passed through onOuter
    private SelectQuery bind(Function<ColumnRef, SelectItem> onOuter) {
        return query.rewrite(new Binder(List.of(Scope.forQuery(query, outer.engine())), onOuter));
    }

    // Walks the subquery, keeping track of the tables at each depth: a column any of them has stays,
    // one only the outer query has goes through onOuter, and one nobody has is reported as unknown
    private final class Binder implements Rewriter {
        private final List<Scope> inner;
        private final Function<ColumnRef, SelectItem> onOuter;

        Binder(List<Scope> inner, Function<ColumnRef, SelectItem> onOuter) {
            this.inner = inner;
            this.onOuter = onOuter;
        }

        public SelectItem column(ColumnRef column) {
            if (column.isStar()) return column;
            for (Scope scope : inner) {
                if (scope.knows(column)) return column;
            }
            if (outer.knows(column)) return onOuter.apply(outer.resolve(column));
            // Reports it the usual way: Unknown column 'x'
            return inner.get(inner.size() - 1).resolve(column);
        }

        public SelectQuery query(SelectQuery nested) {
            List<Scope> deeper = new ArrayList<>(inner);
            deeper.add(Scope.forQuery(nested, outer.engine()));
            return nested.rewrite(new Binder(deeper, onOuter));
        }
    }

    // A subquery's values, for IN: numbers compare by value (2 = 2.0), as in WHERE
    static final class ValueSet {
        private final Set<Object> values = new HashSet<>();
        private final boolean hasEmpty;

        ValueSet(List<Object> list) {
            boolean empty = false;
            for (Object value : list) {
                if (value == null) empty = true;
                else values.add(normalize(value));
            }
            this.hasEmpty = empty;
        }

        boolean isEmpty() {
            return values.isEmpty() && !hasEmpty;
        }

        boolean contains(Object value) {
            return values.contains(normalize(value));
        }

        boolean hasEmpty() {
            return hasEmpty;
        }

        private static Object normalize(Object value) {
            return value instanceof Number n ? (Object) n.doubleValue() : value;
        }
    }
}
