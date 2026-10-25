package db.query;

import db.model.Row;

import java.util.List;

// Flips TRUE and FALSE; unknown stays unknown
public record Not(Expression part) implements Expression {

    public Boolean evaluate(Row row) {
        Boolean result = part.evaluate(row);
        return result == null ? null : !result;
    }

    public Expression resolve(Scope scope) {
        return new Not(part.resolve(scope));
    }

    public Expression rewrite(Rewriter rewriter) {
        return new Not(part.rewrite(rewriter));
    }

    public List<SelectItem> items() {
        return part.items();
    }

    public int precedence() {
        return 3;
    }

    @Override
    public String toString() {
        return "NOT " + Expression.wrap(part, precedence());
    }
}
