package db.query;

import db.model.Row;

import java.util.List;

// column IS NULL / IS NOT NULL: the one test on an empty cell that is TRUE or FALSE, never unknown
public record IsNull(SelectItem item, boolean negated) implements Expression {

    public Boolean evaluate(Row row) {
        return (item.evaluate(row) == null) != negated;
    }

    public Expression resolve(Scope scope) {
        return new IsNull(item.resolve(scope), negated);
    }

    public List<SelectItem> items() {
        return List.of(item);
    }

    public int precedence() {
        return 4;
    }

    @Override
    public String toString() {
        return item + (negated ? " IS NOT NULL" : " IS NULL");
    }
}
