package db.query;

import db.model.Row;

// Flips TRUE and FALSE; unknown stays unknown
public record Not(Expression part) implements Expression {

    public Boolean evaluate(Row row) {
        Boolean result = part.evaluate(row);
        return result == null ? null : !result;
    }

    public Expression resolve(Scope scope) {
        return new Not(part.resolve(scope));
    }

    public int precedence() {
        return 3;
    }

    @Override
    public String toString() {
        return "NOT " + Expression.wrap(part, precedence());
    }
}
