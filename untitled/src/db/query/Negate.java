package db.query;

import db.model.DataType;
import db.model.Row;

import java.util.List;

// -x for an expression; a negative number literal like -5 is a Literal instead
public record Negate(SelectItem operand) implements SelectItem {

    public String key() {
        return toString();
    }

    public SelectItem resolve(Scope scope) {
        Negate resolved = new Negate(operand.resolve(scope));
        DataType type = resolved.operand.type(scope);
        if (!SelectItem.isNumber(type)) throw new IllegalArgumentException("- needs a number, " + resolved.operand + " is " + type);
        return resolved;
    }

    public Object evaluate(Row row) {
        if (SelectItem.stored(this, row)) return row.get(key());
        Object value = operand.evaluate(row);
        if (value == null) return null;
        if (!(value instanceof Number n)) throw new IllegalArgumentException("- needs a number, got " + value);
        if (SelectItem.isWhole(value)) {
            try {
                return SelectItem.whole(Math.negateExact(n.longValue()));
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(toString() + " is out of range");
            }
        }
        return -n.floatValue();
    }

    public DataType type(Scope scope) {
        return operand.type(scope);
    }

    public List<SelectItem> children() {
        return List.of(operand);
    }

    @Override
    public String toString() {
        return "-" + (operand instanceof Arithmetic ? "(" + operand + ")" : operand.toString());
    }
}
