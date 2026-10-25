package db.query;

import db.model.Row;

import java.util.List;
import java.util.stream.Collectors;

// FALSE if any part is FALSE, otherwise unknown if any part is unknown, otherwise TRUE
public record And(List<Expression> parts) implements Expression {

    public Boolean evaluate(Row row) {
        boolean unknown = false;
        for (Expression part : parts) {
            Boolean result = part.evaluate(row);
            if (result == null) unknown = true;
            else if (!result) return false;
        }
        return unknown ? null : true;
    }

    public int precedence() {
        return 2;
    }

    @Override
    public String toString() {
        if (parts.isEmpty()) return "TRUE";
        return parts.stream().map(part -> Expression.wrap(part, precedence())).collect(Collectors.joining(" AND "));
    }
}
