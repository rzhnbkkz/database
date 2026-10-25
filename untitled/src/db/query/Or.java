package db.query;

import db.model.Row;

import java.util.List;
import java.util.stream.Collectors;

// TRUE if any part is TRUE, otherwise unknown if any part is unknown, otherwise FALSE
public record Or(List<Expression> parts) implements Expression {

    public Boolean evaluate(Row row) {
        boolean unknown = false;
        for (Expression part : parts) {
            Boolean result = part.evaluate(row);
            if (result == null) unknown = true;
            else if (result) return true;
        }
        return unknown ? null : false;
    }

    public Expression resolve(Scope scope) {
        return new Or(parts.stream().map(part -> part.resolve(scope)).toList());
    }

    public int precedence() {
        return 1;
    }

    @Override
    public String toString() {
        return parts.stream().map(part -> Expression.wrap(part, precedence())).collect(Collectors.joining(" OR "));
    }
}
