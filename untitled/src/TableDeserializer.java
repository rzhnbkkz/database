import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads tables in the JSON format written by {@link TableSerializer}:
 * {"name": ..., "columns": [{"name": ..., "type": ...}], "rows": [{col: value, ...}]}
 */
public class TableDeserializer {

    public static Table fromJSON(String json) {
        Object root = new JsonReader(json).readDocument();
        Map<String, Object> obj = asObject(root, "table");

        String name = asString(require(obj, "name"), "name");

        List<Column> columns = new ArrayList<>();
        for (Object item : asArray(require(obj, "columns"), "columns")) {
            Map<String, Object> colObj = asObject(item, "column");
            String colName = asString(require(colObj, "name"), "column name");
            String typeName = asString(require(colObj, "type"), "column type");
            DataType type;
            try {
                type = DataType.valueOf(typeName.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown column type: " + typeName);
            }
            Object primaryKey = colObj.getOrDefault("primaryKey", false);
            if (!(primaryKey instanceof Boolean)) throw new IllegalArgumentException("Expected primaryKey to be true or false");
            columns.add(new Column(colName, type, (Boolean) primaryKey));
        }

        Table table = new Table(name, columns);

        Object rowsValue = obj.get("rows");
        if (rowsValue == null) return table;
        for (Object item : asArray(rowsValue, "rows")) {
            Map<String, Object> rowObj = asObject(item, "row");
            Map<String, Object> data = new LinkedHashMap<>();
            for (Column col : columns) {
                Object value = convert(rowObj.get(col.getName()), col);
                // null and missing values are both stored as absent
                if (value != null) data.put(col.getName(), value);
            }
            table.insert(new Row(data));
        }
        return table;
    }

    public static Table loadFromFile(String path) throws IOException {
        return fromJSON(Files.readString(Path.of(path)));
    }

    // Turn a raw JSON value into the Java type the column expects
    private static Object convert(Object value, Column col) {
        if (value == null) return null;
        String colName = col.getName();
        switch (col.getDataType()) {
            case INTEGER -> {
                if (value instanceof BigDecimal n) {
                    try {
                        return n.intValueExact();
                    } catch (ArithmeticException e) {
                        throw new IllegalArgumentException("Column '" + colName + "' expects INTEGER, got " + n);
                    }
                }
            }
            case FLOAT -> {
                if (value instanceof BigDecimal n) return n.floatValue();
            }
            case VARCHAR -> {
                if (value instanceof String s) return s;
            }
            case BOOLEAN -> {
                if (value instanceof Boolean b) return b;
            }
        }
        throw new IllegalArgumentException("Column '" + colName + "' expects " + col.getDataType() + ", got " + value);
    }

    private static Object require(Map<String, Object> obj, String key) {
        if (!obj.containsKey(key)) throw new IllegalArgumentException("Missing field: " + key);
        return obj.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value, String what) {
        if (value instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("Expected " + what + " to be a JSON object");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asArray(Object value, String what) {
        if (value instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException("Expected " + what + " to be a JSON array");
    }

    private static String asString(Object value, String what) {
        if (value instanceof String s) return s;
        throw new IllegalArgumentException("Expected " + what + " to be a string");
    }

    /**
     * Minimal JSON parser. Produces Map (objects), List (arrays), String,
     * BigDecimal (numbers), Boolean and null.
     */
    private static class JsonReader {
        private final String s;
        private int pos = 0;

        JsonReader(String s) {
            this.s = s;
        }

        Object readDocument() {
            Object value = readValue();
            skipWhitespace();
            if (pos != s.length()) throw error("Unexpected trailing content");
            return value;
        }

        private Object readValue() {
            skipWhitespace();
            if (pos >= s.length()) throw error("Unexpected end of input");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (c == '-' || Character.isDigit(c)) yield readNumber();
                    throw error("Unexpected character '" + c + "'");
                }
            };
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') throw error("Expected string key");
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                char c = next();
                if (c == '}') return map;
                if (c != ',') throw error("Expected ',' or '}'");
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                char c = next();
                if (c == ']') return list;
                if (c != ',') throw error("Expected ',' or ']'");
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char esc = next();
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw error("Invalid unicode escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException e) {
                            throw error("Invalid unicode escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("Invalid escape '\\" + esc + "'");
                }
            }
        }

        private BigDecimal readNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            try {
                return new BigDecimal(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw error("Invalid number");
            }
        }

        private Object readLiteral(String word, Object value) {
            if (!s.startsWith(word, pos)) throw error("Unexpected token");
            pos += word.length();
            return value;
        }

        private void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        private char peek() {
            if (pos >= s.length()) throw error("Unexpected end of input");
            return s.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) throw error("Expected '" + c + "'");
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException("Invalid JSON at position " + pos + ": " + message);
        }
    }
}
