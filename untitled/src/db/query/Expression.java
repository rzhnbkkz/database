package db.query;

import db.model.Row;

import java.util.List;

// A WHERE clause: a Condition, or AND / OR / NOT combining other expressions.
// Like SQL, evaluation has three results: TRUE, FALSE, or null for unknown, which comes from
// comparing an empty cell. Only TRUE selects a row, so NOT (name = 'Bob') skips rows without a name.
public interface Expression {
    // No WHERE clause: an AND of nothing is TRUE for every row
    Expression ALL_ROWS = new And(List.of());

    Boolean evaluate(Row row);

    default boolean matches(Row row) {
        return Boolean.TRUE.equals(evaluate(row));
    }

    // Binding strength for toString: OR 1, AND 2, NOT 3, comparison 4
    int precedence();

    // Child's text, in parentheses when it binds more loosely than its parent
    static String wrap(Expression child, int parentPrecedence) {
        return child.precedence() < parentPrecedence ? "(" + child + ")" : child.toString();
    }
}
