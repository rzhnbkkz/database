package db.query;

import db.model.DataType;
import db.model.Row;

import java.util.List;
import java.util.Objects;

// COUNT(*), COUNT(column), SUM(column) or AVG(column) over the rows of one group.
// Like SQL, empty cells are skipped: COUNT(column) doesn't count them, and SUM or AVG of no values is empty.
public record Aggregate(Function function, ColumnRef column) implements SelectItem {
    public enum Function { COUNT, SUM, AVG }

    // COUNT(*)
    public static Aggregate countAll() {
        return new Aggregate(Function.COUNT, null);
    }

    public Aggregate resolve(Scope scope) {
        if (column == null) return this;
        ColumnRef resolved = scope.resolve(column);
        if (function != Function.COUNT) {
            DataType type = scope.column(column).getDataType();
            if (type != DataType.INTEGER && type != DataType.FLOAT) {
                throw new IllegalArgumentException(function + " needs a number column, '" + column + "' is " + type);
            }
        }
        return new Aggregate(function, resolved);
    }

    // INTEGER sums stay whole numbers; AVG and FLOAT sums are FLOAT
    public Object compute(List<Row> rows) {
        if (column == null) return rows.size();
        List<Object> values = rows.stream().map(row -> row.get(column.key())).filter(Objects::nonNull).toList();
        if (function == Function.COUNT) return values.size();
        if (values.isEmpty()) return null;
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

    @Override
    public String toString() {
        return function + "(" + (column == null ? "*" : column) + ")";
    }
}
