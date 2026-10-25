package db.query;

import db.model.DataType;
import db.model.Row;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// A built-in function like UPPER(name) or ROUND(price, 2). As in MySQL, an empty argument gives an empty
// result, except for COALESCE, IFNULL and NULLIF, which are about empty values. Text functions take any
// value as its text (UPPER(42) is '42').
public record FunctionCall(Function function, List<SelectItem> args) implements SelectItem {
    public enum Function {
        UPPER(1, 1), LOWER(1, 1), LENGTH(1, 1), TRIM(1, 1), CONCAT(1, Integer.MAX_VALUE), SUBSTRING(2, 3), REPLACE(3, 3),
        ABS(1, 1), ROUND(1, 2), FLOOR(1, 1), CEIL(1, 1),
        COALESCE(1, Integer.MAX_VALUE), IFNULL(2, 2), NULLIF(2, 2);

        private final int minArgs, maxArgs;

        Function(int minArgs, int maxArgs) {
            this.minArgs = minArgs;
            this.maxArgs = maxArgs;
        }

        // The function with this name, also under MySQL's other names; null if there is none
        public static Function named(String name) {
            String upper = name.toUpperCase();
            return switch (upper) {
                case "SUBSTR" -> SUBSTRING;
                case "CEILING" -> CEIL;
                case "CHAR_LENGTH" -> LENGTH;
                default -> {
                    for (Function function : values()) {
                        if (function.name().equals(upper)) yield function;
                    }
                    yield null;
                }
            };
        }
    }

    public FunctionCall {
        if (args.size() < function.minArgs || args.size() > function.maxArgs) {
            String expected = function.minArgs == function.maxArgs ? String.valueOf(function.minArgs)
                    : function.maxArgs == Integer.MAX_VALUE ? "at least " + function.minArgs
                    : function.minArgs + " or " + function.maxArgs;
            boolean one = expected.equals("1") || expected.equals("at least 1");
            throw new IllegalArgumentException(function + " takes " + expected + (one ? " argument" : " arguments")
                    + ", got " + args.size());
        }
        args = List.copyOf(args);
    }

    public String key() {
        return toString();
    }

    public SelectItem resolve(Scope scope) {
        List<SelectItem> resolved = new ArrayList<>();
        for (SelectItem arg : args) resolved.add(arg.resolve(scope));
        FunctionCall call = new FunctionCall(function, resolved);
        // Number arguments: ABS(x), ROUND(x, d), FLOOR(x), CEIL(x), and SUBSTRING's position and length
        List<SelectItem> numbers = switch (function) {
            case ABS, ROUND, FLOOR, CEIL -> resolved;
            case SUBSTRING -> resolved.subList(1, resolved.size());
            default -> List.of();
        };
        for (SelectItem arg : numbers) {
            DataType type = arg.type(scope);
            if (!SelectItem.isNumber(type)) throw new IllegalArgumentException(function + " needs a number, " + arg + " is " + type);
        }
        if (function == Function.ROUND && resolved.size() == 2 && resolved.get(1).type(scope) == DataType.FLOAT) {
            throw new IllegalArgumentException("ROUND needs a whole number of decimals, " + resolved.get(1) + " is FLOAT");
        }
        return call;
    }

    public Object evaluate(Row row) {
        if (SelectItem.stored(this, row)) return row.get(key());
        List<Object> values = new ArrayList<>();
        for (SelectItem arg : args) values.add(arg.evaluate(row));
        switch (function) {
            case COALESCE, IFNULL -> {
                for (Object value : values) {
                    if (value != null) return value;
                }
                return null;
            }
            case NULLIF -> {
                Integer cmp = values.get(0) == null || values.get(1) == null ? null : Condition.compare(values.get(0), values.get(1));
                return cmp != null && cmp == 0 ? null : values.get(0);
            }
            default -> {
            }
        }
        if (values.contains(null)) return null;
        Object x = values.get(0);
        return switch (function) {
            case UPPER -> x.toString().toUpperCase();
            case LOWER -> x.toString().toLowerCase();
            case LENGTH -> x.toString().length();
            case TRIM -> x.toString().strip();
            case CONCAT -> values.stream().map(Object::toString).collect(Collectors.joining());
            case REPLACE -> x.toString().replace(values.get(1).toString(), values.get(2).toString());
            case SUBSTRING -> substring(x.toString(), number(values.get(1)).intValue(),
                    values.size() == 3 ? number(values.get(2)).intValue() : Integer.MAX_VALUE);
            case ABS -> SelectItem.isWhole(x) ? SelectItem.whole(Math.abs(number(x).longValue())) : (Object) Math.abs(number(x).floatValue());
            case ROUND -> round(number(x), values.size() == 2 ? number(values.get(1)).intValue() : 0);
            case FLOOR -> SelectItem.whole((long) Math.floor(number(x).doubleValue()));
            case CEIL -> SelectItem.whole((long) Math.ceil(number(x).doubleValue()));
            case COALESCE, IFNULL, NULLIF -> throw new AssertionError();
        };
    }

    private Number number(Object value) {
        if (!(value instanceof Number n)) throw new IllegalArgumentException(function + " needs a number, got " + value);
        return n;
    }

    // MySQL's SUBSTRING: positions start at 1, a negative position counts from the end, 0 gives ''
    private static String substring(String text, int position, int length) {
        if (position == 0 || length <= 0) return "";
        int start = position > 0 ? position - 1 : text.length() + position;
        if (start < 0 || start >= text.length()) return "";
        return text.substring(start, (int) Math.min(text.length(), (long) start + length));
    }

    // Halves round away from zero (2.5 -> 3, -2.5 -> -3). With no decimals, or a negative number of them
    // (ROUND(1234, -2) = 1200), the result is a whole number; with decimals it's FLOAT.
    private static Object round(Number value, int decimals) {
        BigDecimal rounded = new BigDecimal(value.toString()).setScale(decimals, RoundingMode.HALF_UP);
        if (decimals > 0) return SelectItem.decimal(rounded.doubleValue(), "ROUND(" + value + ", " + decimals + ")");
        try {
            return SelectItem.whole(rounded.longValueExact());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("ROUND(" + value + ") is out of range");
        }
    }

    public DataType type(Scope scope) {
        return switch (function) {
            case UPPER, LOWER, TRIM, CONCAT, SUBSTRING, REPLACE -> DataType.VARCHAR;
            case LENGTH, FLOOR, CEIL -> DataType.INTEGER;
            case ABS -> args.get(0).type(scope);
            case ROUND -> args.size() == 2 && !(args.get(1) instanceof Literal l && l.value() instanceof Number n && n.intValue() <= 0)
                    ? DataType.FLOAT : DataType.INTEGER;
            case COALESCE, IFNULL, NULLIF -> {
                for (SelectItem arg : args) {
                    DataType type = arg.type(scope);
                    if (type != null) yield type;
                }
                yield null;
            }
        };
    }

    public List<SelectItem> children() {
        return args;
    }

    @Override
    public String toString() {
        return function + "(" + args.stream().map(SelectItem::toString).collect(Collectors.joining(", ")) + ")";
    }
}
