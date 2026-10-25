package db.query;

import db.model.Row;

import java.util.List;

public class Condition implements Expression {
    public enum Operator {
        EQUALS("="), NOT_EQUALS("!="), GREATER(">"), LESS("<"), GREATER_OR_EQUAL(">="), LESS_OR_EQUAL("<=");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String getSymbol() {
            return symbol;
        }

        public static Operator fromSymbol(String symbol) {
            for (Operator op : values()) {
                if (op.symbol.equals(symbol)) return op;
            }
            throw new IllegalArgumentException("Unknown operator: " + symbol);
        }
    }

    // A column, or in HAVING also an aggregate (COUNT(*) > 1)
    private SelectItem column;
    private Operator operator;
    // A literal, a ColumnRef to compare two columns (u.id = o.user_id), or in HAVING an aggregate
    private Object value;

    public Condition(SelectItem column, Operator operator, Object value) {
        this.column = column;
        this.operator = operator;
        this.value = value;
    }

    public Condition(String column, Operator operator, Object value) {
        this(new ColumnRef(column), operator, value);
    }

    public Condition(String column, Object value) {
        this(column, Operator.EQUALS, value);
    }

    public String getColumn() {
        return column.key();
    }

    public SelectItem getLeft() {
        return column;
    }

    public Operator getOperator() {
        return operator;
    }

    public Object getValue() {
        return value;
    }

    public Boolean evaluate(Row row) {
        Object cell = column.evaluate(row);
        Object other = value instanceof SelectItem item ? item.evaluate(row) : value;
        // Like SQL NULL, comparing an empty cell is unknown, so it never matches, even with NOT
        if (cell == null || other == null) return null;
        Integer cmp = compare(cell, other);
        if (operator == Operator.NOT_EQUALS) return cmp == null || cmp != 0;
        if (cmp == null) return false;
        return switch (operator) {
            case EQUALS -> cmp == 0;
            case GREATER -> cmp > 0;
            case LESS -> cmp < 0;
            case GREATER_OR_EQUAL -> cmp >= 0;
            case LESS_OR_EQUAL -> cmp <= 0;
            case NOT_EQUALS -> throw new AssertionError();
        };
    }

    // Returns null when the two values can't be compared (different kinds); ORDER BY uses it too
    static Integer compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue());
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return x.compareTo(y);
        return null;
    }

    public Condition resolve(Scope scope) {
        Object resolved = value;
        if (value instanceof ColumnRef ref) {
            // A bare word on the right is read as a column, but was probably meant as text
            if (ref.table() == null && !scope.hasColumn(ref.column())) {
                throw new IllegalArgumentException("Unknown column '" + ref.column()
                        + "' (to compare with text, put it in quotes: '" + ref.column() + "')");
            }
        }
        if (value instanceof SelectItem item) resolved = item.resolve(scope);
        return new Condition(column.resolve(scope), operator, resolved);
    }

    public List<SelectItem> items() {
        return value instanceof SelectItem item ? List.of(column, item) : List.of(column);
    }

    public int precedence() {
        return 4;
    }

    // Compact for plain columns and values (id>1), spaced when a side is an expression (price * 2 > 10)
    @Override
    public String toString() {
        boolean simple = (column instanceof ColumnRef || column instanceof Aggregate)
                && (!(value instanceof SelectItem) || value instanceof ColumnRef || value instanceof Aggregate);
        return simple ? column + operator.getSymbol() + value : column + " " + operator.getSymbol() + " " + value;
    }
}
