package db.query;

import db.model.DataType;
import db.model.Row;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

// COUNT(*), COUNT(column), SUM, AVG, MIN or MAX of a column, over the rows of one group.
// Like SQL, empty cells are skipped: COUNT(column) doesn't count them, and the others are empty for no values.
// With DISTINCT, each value is used once: COUNT(DISTINCT city) counts different cities.
public record Aggregate(Function function, ColumnRef column, boolean distinct) implements SelectItem {
    public enum Function { COUNT, SUM, AVG, MIN, MAX }

    public Aggregate(Function function, ColumnRef column) {
        this(function, column, false);
    }

    // COUNT(*)
    public static Aggregate countAll() {
        return new Aggregate(Function.COUNT, null);
    }

    public Aggregate resolve(Scope scope) {
        if (column == null) return this;
        ColumnRef resolved = scope.resolve(column);
        // MIN and MAX compare like WHERE does, so any type works
        if (function == Function.SUM || function == Function.AVG) {
            DataType type = scope.column(column).getDataType();
            if (type != DataType.INTEGER && type != DataType.FLOAT) {
                throw new IllegalArgumentException(function + " needs a number column, '" + column + "' is " + type);
            }
        }
        return new Aggregate(function, resolved, distinct);
    }

    // INTEGER sums stay whole numbers; AVG and FLOAT sums are FLOAT
    public Object compute(List<Row> rows) {
        if (column == null) return rows.size();
        List<Object> values = rows.stream().map(row -> row.get(column.key())).filter(Objects::nonNull).toList();
        // A column holds one type, so equal values are equal objects
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
        if (values.get(0) instanceof Integer) {
            long sum = 0;
            for (Object value : values) sum += (Integer) value;
            if (function == Function.AVG) return (float) ((double) sum / values.size());
            // Too big for INTEGER: give the exact total as a larger whole number rather than overflow
            return sum == (int) sum ? (Object) (int) sum : (Object) sum;
        }
        double sum = 0;
        for (Object value : values) sum += ((Number) value).doubleValue();
        return (float) (function == Function.SUM ? sum : sum / values.size());
    }

    public String key() {
        return toString();
    }

    @Override
    public String toString() {
        return function + "(" + (distinct ? "DISTINCT " : "") + (column == null ? "*" : column) + ")";
    }
}
