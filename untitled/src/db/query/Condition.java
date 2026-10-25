package db.query;

import db.model.Row;

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

    private String column;
    private Operator operator;
    private Object value;

    public Condition(String column, Operator operator, Object value) {
        this.column = column;
        this.operator = operator;
        this.value = value;
    }

    public Condition(String column, Object value) {
        this(column, Operator.EQUALS, value);
    }

    public String getColumn() {
        return column;
    }

    public Operator getOperator() {
        return operator;
    }

    public Object getValue() {
        return value;
    }

    public Boolean evaluate(Row row) {
        Object cell = row.get(column);
        // Like SQL NULL, comparing an empty cell is unknown, so it never matches, even with NOT
        if (cell == null || value == null) return null;
        Integer cmp = compare(cell, value);
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

    // Returns null when the two values can't be compared (different kinds)
    private static Integer compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue());
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return x.compareTo(y);
        return null;
    }

    public int precedence() {
        return 4;
    }

    @Override
    public String toString() {
        return column + operator.getSymbol() + value;
    }
}
