package db.query;

import db.model.DataType;
import db.model.Row;

import java.util.List;

// a + b, a - b, a * b, a / b or a % b, following MySQL: an empty operand gives an empty result, / always
// gives a fraction (7 / 2 = 3.5), dividing by zero gives an empty result, and whole numbers stay whole
// otherwise. A FLOAT operand makes the result FLOAT. Too big for a whole number is an error.
public record Arithmetic(SelectItem left, Operator operator, SelectItem right) implements SelectItem {
    public enum Operator {
        ADD("+", 1), SUBTRACT("-", 1), MULTIPLY("*", 2), DIVIDE("/", 2), MODULO("%", 2);

        private final String symbol;
        // + and - bind looser than * / %
        private final int precedence;

        Operator(String symbol, int precedence) {
            this.symbol = symbol;
            this.precedence = precedence;
        }

        public String symbol() {
            return symbol;
        }

        public static Operator fromSymbol(String symbol) {
            for (Operator operator : values()) {
                if (operator.symbol.equals(symbol)) return operator;
            }
            return null;
        }
    }

    public String key() {
        return toString();
    }

    public SelectItem resolve(Scope scope) {
        Arithmetic resolved = new Arithmetic(left.resolve(scope), operator, right.resolve(scope));
        for (SelectItem side : List.of(resolved.left, resolved.right)) {
            DataType type = side.type(scope);
            if (!SelectItem.isNumber(type)) {
                throw new IllegalArgumentException(operator.symbol + " needs numbers, " + side + " is " + type);
            }
        }
        return resolved;
    }

    public SelectItem rewrite(Rewriter rewriter) {
        return new Arithmetic(left.rewrite(rewriter), operator, right.rewrite(rewriter));
    }

    public Object evaluate(Row row) {
        if (SelectItem.stored(this, row)) return row.get(key());
        Object a = left.evaluate(row), b = right.evaluate(row);
        if (a == null || b == null) return null;
        if (!(a instanceof Number x) || !(b instanceof Number y)) {
            throw new IllegalArgumentException(operator.symbol + " needs numbers, got " + a + " and " + b);
        }
        if (operator == Operator.DIVIDE) {
            return y.doubleValue() == 0 ? null : SelectItem.decimal(x.doubleValue() / y.doubleValue(), toString());
        }
        if (operator == Operator.MODULO && y.doubleValue() == 0) return null;
        if (SelectItem.isWhole(a) && SelectItem.isWhole(b)) {
            long p = x.longValue(), q = y.longValue();
            try {
                return SelectItem.whole(switch (operator) {
                    case ADD -> Math.addExact(p, q);
                    case SUBTRACT -> Math.subtractExact(p, q);
                    case MULTIPLY -> Math.multiplyExact(p, q);
                    case MODULO -> p % q;
                    case DIVIDE -> throw new AssertionError();
                });
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(toString() + " is out of range");
            }
        }
        double p = x.doubleValue(), q = y.doubleValue();
        return SelectItem.decimal(switch (operator) {
            case ADD -> p + q;
            case SUBTRACT -> p - q;
            case MULTIPLY -> p * q;
            case MODULO -> p % q;
            case DIVIDE -> throw new AssertionError();
        }, toString());
    }

    public DataType type(Scope scope) {
        if (operator == Operator.DIVIDE) return DataType.FLOAT;
        return SelectItem.numberType(left.type(scope), right.type(scope));
    }

    public List<SelectItem> children() {
        return List.of(left, right);
    }

    // With parentheses only where needed: (a + b) * c, a - (b - c)
    @Override
    public String toString() {
        return wrap(left, false) + " " + operator.symbol + " " + wrap(right, true);
    }

    private String wrap(SelectItem side, boolean isRight) {
        if (side instanceof Arithmetic inner) {
            // The right side of - / % also needs them at equal precedence: a - (b + c)
            boolean needed = inner.operator.precedence < operator.precedence
                    || (isRight && inner.operator.precedence == operator.precedence && operator != Operator.ADD && operator != Operator.MULTIPLY);
            if (needed) return "(" + inner + ")";
        }
        return side.toString();
    }
}
