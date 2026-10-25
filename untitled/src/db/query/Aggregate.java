package db.query;

import db.model.DataType;
import db.model.Row;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

// COUNT(*), or COUNT, SUM, AVG, MIN or MAX of a value (a column, or an expression like price * qty), over
// the rows of one group. Like SQL, empty values are skipped: COUNT(x) doesn't count them, and the others
// are empty for no values. With DISTINCT, each value is used once: COUNT(DISTINCT city) counts different cities.
public record Aggregate(Function function, SelectItem argument, boolean distinct) implements SelectItem {
    public enum Function { COUNT, SUM, AVG, MIN, MAX }

    public Aggregate(Function function, SelectItem argument) {
        this(function, argument, false);
    }

    // COUNT(*)
    public static Aggregate countAll() {
        return new Aggregate(Function.COUNT, null);
    }

    public Aggregate resolve(Scope scope) {
        if (argument == null) return this;
        SelectItem resolved = argument.resolve(scope);
        // MIN and MAX compare like WHERE does, so any type works
        if (function == Function.SUM || function == Function.AVG) {
            DataType type = resolved.type(scope);
            if (!SelectItem.isNumber(type)) {
                throw new IllegalArgumentException(function + " needs a number column, '" + argument + "' is " + type);
            }
        }
        return new Aggregate(function, resolved, distinct);
    }

    public Aggregate rewrite(Rewriter rewriter) {
        return argument == null ? this : new Aggregate(function, argument.rewrite(rewriter), distinct);
    }

    // In a group row, the result is stored under the aggregate's name
    public Object evaluate(Row row) {
        return row.get(key());
    }

    public DataType type(Scope scope) {
        return switch (function) {
            case COUNT -> DataType.INTEGER;
            case AVG -> DataType.FLOAT;
            case SUM, MIN, MAX -> argument.type(scope);
        };
    }

    public List<SelectItem> children() {
        return List.of();
    }

    // INTEGER sums stay whole numbers; AVG and FLOAT sums are FLOAT
    public Object compute(List<Row> rows) {
        if (argument == null) return rows.size();
        List<Object> values = rows.stream().map(argument::evaluate).filter(Objects::nonNull).toList();
        // Values of one expression have one type, so equal values are equal objects
        if (distinct) values = List.copyOf(new LinkedHashSet<>(values));
        if (function == Function.COUNT) return values.size();
        if (values.isEmpty()) return null;
        if (function == Function.MIN || function == Function.MAX) {
            Object best = values.get(0);
            for (Object value : values) {
                Integer cmp = Condition.compare(value, best);
                if (cmp != null && (function == Function.MIN ? cmp < 0 : cmp > 0)) best = value;
            }
            return best;
        }
        if (values.stream().allMatch(SelectItem::isWhole)) {
            long sum = 0;
            try {
                for (Object value : values) sum = Math.addExact(sum, ((Number) value).longValue());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException(this + " is out of range");
            }
            if (function == Function.AVG) return (float) ((double) sum / values.size());
            // Too big for INTEGER: give the exact total as a larger whole number rather than overflow
            return SelectItem.whole(sum);
        }
        double sum = 0;
        for (Object value : values) sum += ((Number) value).doubleValue();
        return SelectItem.decimal(function == Function.SUM ? sum : sum / values.size(), toString());
    }

    public String key() {
        return toString();
    }

    @Override
    public String toString() {
        return function + "(" + (distinct ? "DISTINCT " : "") + (argument == null ? "*" : argument) + ")";
    }
}
