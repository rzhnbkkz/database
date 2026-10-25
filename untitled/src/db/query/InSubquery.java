package db.query;

import db.model.Row;

import java.util.ArrayList;
import java.util.List;

// x [NOT] IN (SELECT ...), with SQL's rules for empty values: if the subquery gives no rows it's FALSE
// (NOT IN is TRUE) even for an empty x; otherwise an empty x is unknown, and so is a value not found
// when the subquery gave an empty value, so NOT IN never matches then. runner is null until resolved.
public record InSubquery(SelectItem item, SelectQuery query, boolean negated, SubqueryRunner runner) implements Expression {

    public InSubquery(SelectItem item, SelectQuery query, boolean negated) {
        this(item, query, negated, null);
    }

    public Boolean evaluate(Row row) {
        SubqueryRunner.ValueSet values = runner.values(row);
        if (values.isEmpty()) return negated;
        Object value = item.evaluate(row);
        if (value == null) return null;
        if (values.contains(value)) return !negated;
        return values.hasEmpty() ? null : negated;
    }

    public Expression resolve(Scope scope) {
        return new InSubquery(item.resolve(scope), query, negated, new SubqueryRunner(query, scope));
    }

    public Expression rewrite(Rewriter rewriter) {
        return new InSubquery(item.rewrite(rewriter), rewriter.query(query), negated);
    }

    // What it compares, and the outer columns the subquery uses
    public List<SelectItem> items() {
        List<SelectItem> items = new ArrayList<>(List.of(item));
        if (runner != null) items.addAll(runner.outerColumns());
        return items;
    }

    public int precedence() {
        return 4;
    }

    @Override
    public String toString() {
        return item + (negated ? " NOT IN (" : " IN (") + query + ")";
    }
}
