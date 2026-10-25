package db.query;

import db.model.DataType;
import db.model.Row;

import java.util.List;

// (SELECT ...) used as a value: the single value of a one-column query. No rows give an empty value,
// more than one is an error. runner is null until resolved.
public record Subquery(SelectQuery query, SubqueryRunner runner) implements SelectItem {

    public Subquery(SelectQuery query) {
        this(query, null);
    }

    public String key() {
        return toString();
    }

    public SelectItem resolve(Scope scope) {
        return new Subquery(query, new SubqueryRunner(query, scope));
    }

    public SelectItem rewrite(Rewriter rewriter) {
        return new Subquery(rewriter.query(query));
    }

    public Object evaluate(Row row) {
        if (SelectItem.stored(this, row)) return row.get(key());
        return runner.value(row);
    }

    // Not known until it runs
    public DataType type(Scope scope) {
        return null;
    }

    // The outer columns it uses: once rows are grouped, they must be GROUP BY columns like any other
    public List<SelectItem> children() {
        return runner == null ? List.of() : List.copyOf(runner.outerColumns());
    }

    @Override
    public String toString() {
        return "(" + query + ")";
    }
}
