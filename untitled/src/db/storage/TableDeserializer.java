package db.storage;

import db.model.Change;
import db.model.Column;
import db.model.DataType;
import db.model.Row;
import db.model.Table;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads tables in the JSON format written by {@link TableSerializer}:
 * {"name": ..., "columns": [{"name": ..., "type": ...}], "indexes": [{"name": ..., "columns": [...], "unique": true}],
 *  "rows": [{col: value, ...}]}
 * "indexes" and "rows" may be left out, and so may an index's "unique". A column may also have "notNull",
 * "default" and "autoIncrement", and a table with an AUTO_INCREMENT column a "nextAutoIncrement". Files from before multi-column
 * indexes, with "column": "name" instead of "columns", still load.
 */
public class TableDeserializer {

    public static Table fromJSON(String json) {
        return fromJSON(new JsonReader(json).readDocument());
    }

    // A table already parsed from JSON, such as one inside a snapshot or a log record
    static Table fromJSON(Object root) {
        Map<String, Object> obj = asObject(root, "table");

        String name = asString(require(obj, "name"), "name");

        List<Column> columns = new ArrayList<>();
        for (Object item : asArray(require(obj, "columns"), "columns")) columns.add(columnFromJSON(item));

        Table table = new Table(name, columns);

        Object next = obj.get("nextAutoIncrement");
        if (next != null) {
            if (!(next instanceof BigDecimal n) || n.scale() > 0 || n.signum() <= 0) {
                throw new IllegalArgumentException("Expected nextAutoIncrement to be a whole number above 0");
            }
            table.restoreNextAutoIncrement(n.longValueExact());
        }

        Object indexesValue = obj.get("indexes");
        if (indexesValue != null) {
            for (Object item : asArray(indexesValue, "indexes")) {
                Map<String, Object> indexObj = asObject(item, "index");
                List<String> indexColumns = new ArrayList<>();
                if (indexObj.containsKey("columns")) {
                    for (Object column : asArray(indexObj.get("columns"), "index columns")) indexColumns.add(asString(column, "index column"));
                } else {
                    indexColumns.add(asString(require(indexObj, "column"), "index column"));
                }
                Object unique = indexObj.getOrDefault("unique", false);
                if (!(unique instanceof Boolean)) throw new IllegalArgumentException("Expected unique to be true or false");
                table.createIndex(asString(require(indexObj, "name"), "index name"), indexColumns, (Boolean) unique);
            }
        }

        Object rowsValue = obj.get("rows");
        if (rowsValue == null) return table;
        // From a snapshot: the rows keep their ids, and were checked when they were first written
        if (obj.containsKey("rowIds")) {
            List<Object> ids = asArray(obj.get("rowIds"), "rowIds");
            List<Object> rows = asArray(rowsValue, "rows");
            if (ids.size() != rows.size()) throw new IllegalArgumentException("Expected one row id per row");
            Map<Long, Row> byId = new LinkedHashMap<>();
            for (int i = 0; i < rows.size(); i++) byId.put(asId(ids.get(i)), rowFromJSON(rows.get(i), columns));
            table.apply(new Change.Insert(byId, table.getNextAutoIncrement()));
            return table;
        }
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

    static Column columnFromJSON(Object item) {
        Map<String, Object> colObj = asObject(item, "column");
        String colName = asString(require(colObj, "name"), "column name");
        String typeName = asString(require(colObj, "type"), "column type");
        DataType type;
        try {
            type = DataType.valueOf(typeName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown column type: " + typeName);
        }
        boolean primaryKey = flag(colObj, "primaryKey");
        Column plain = new Column(colName, type, primaryKey);
        return new Column(colName, type, primaryKey, flag(colObj, "notNull"), convert(colObj.get("default"), plain),
                flag(colObj, "autoIncrement"));
    }

    // A row written by TableSerializer.rowToJSON, with each value turned into its column's type
    static Row rowFromJSON(Object item, List<Column> columns) {
        Map<String, Object> rowObj = asObject(item, "row");
        Map<String, Object> data = new LinkedHashMap<>();
        for (Column col : columns) {
            Object value = convert(rowObj.get(col.getName()), col);
            if (value != null) data.put(col.getName(), value);
        }
        for (String key : rowObj.keySet()) {
            if (columns.stream().noneMatch(col -> col.getName().equals(key))) throw new IllegalArgumentException("Unknown column '" + key + "'");
        }
        return new Row(data);
    }

    // A row id or log sequence number: a whole number, zero or more
    static long asId(Object value) {
        if (!(value instanceof BigDecimal n) || n.scale() > 0 || n.signum() < 0) {
            throw new IllegalArgumentException("Expected a whole number, got " + value);
        }
        return n.longValueExact();
    }

    // An optional true/false property, false when left out
    private static boolean flag(Map<String, Object> obj, String key) {
        Object value = obj.getOrDefault(key, false);
        if (!(value instanceof Boolean b)) throw new IllegalArgumentException("Expected " + key + " to be true or false");
        return b;
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

    static Object require(Map<String, Object> obj, String key) {
        if (!obj.containsKey(key)) throw new IllegalArgumentException("Missing field: " + key);
        return obj.get(key);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value, String what) {
        if (value instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalArgumentException("Expected " + what + " to be a JSON object");
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object value, String what) {
        if (value instanceof List<?> l) return (List<Object>) l;
        throw new IllegalArgumentException("Expected " + what + " to be a JSON array");
    }

    static String asString(Object value, String what) {
        if (value instanceof String s) return s;
        throw new IllegalArgumentException("Expected " + what + " to be a string");
    }

    /**
     * Minimal JSON parser. Produces Map (objects), List (arrays), String,
     * BigDecimal (numbers), Boolean and null.
     */
    static class JsonReader {
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
