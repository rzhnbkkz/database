package db.query;

import db.model.DataType;
import db.model.Row;

import java.util.List;

// A fixed value: 42, -1.5, 'text', TRUE, FALSE or NULL (null)
public record Literal(Object value) implements SelectItem {

    public String key() {
        return toString();
    }

    public Literal resolve(Scope scope) {
        return this;
    }

    public Literal rewrite(Rewriter rewriter) {
        return this;
    }

    public Object evaluate(Row row) {
        return value;
    }

    public DataType type(Scope scope) {
        return of(value);
    }

    // The type of a stored or literal value
    static DataType of(Object value) {
        if (value instanceof Integer || value instanceof Long) return DataType.INTEGER;
        if (value instanceof Float) return DataType.FLOAT;
        if (value instanceof String) return DataType.VARCHAR;
        if (value instanceof Boolean) return DataType.BOOLEAN;
        return null;
    }

    public List<SelectItem> children() {
        return List.of();
    }

    // As it would be written in SQL
    @Override
    public String toString() {
        if (value == null) return "NULL";
        if (value instanceof String s) return "'" + s.replace("'", "''") + "'";
        if (value instanceof Boolean b) return b ? "TRUE" : "FALSE";
        return value.toString();
    }
}
