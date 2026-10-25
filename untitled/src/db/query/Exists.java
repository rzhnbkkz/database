package db.query;

import db.model.Row;

import java.util.List;

// EXISTS (SELECT ...): TRUE if the subquery gives at least one row, never unknown. NOT EXISTS is Not of it.
// runner is null until resolved.
public record Exists(SelectQuery query, SubqueryRunner runner) implements Expression {

    public Exists(SelectQuery query) {
        this(query, null);
    }

    public Boolean evaluate(Row row) {
        return runner.exists(row);
    }

    public Expression resolve(Scope scope) {
        return new Exists(query, new SubqueryRunner(query, scope));
    }

    public Expression rewrite(Rewriter rewriter) {
        return new Exists(rewriter.query(query));
    }

    // The outer columns the subquery uses
    public List<SelectItem> items() {
        return runner == null ? List.of() : List.copyOf(runner.outerColumns());
    }

    public int precedence() {
        return 4;
    }

    @Override
    public String toString() {
        return "EXISTS (" + query + ")";
    }
}
