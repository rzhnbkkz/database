import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class Table {
    private final String name;
    private final List<Column> columns;
    private final List<Row> rows;
    private final Column primaryKey;
    // Primary key values of every stored row, for fast uniqueness checks
    private final Set<Object> keys = new HashSet<>();

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = new ArrayList<>(columns);
        this.rows = new ArrayList<>();
        List<Column> keyColumns = columns.stream().filter(Column::isPrimaryKey).toList();
        if (keyColumns.size() > 1) throw new IllegalArgumentException("A table can have only one primary key");
        this.primaryKey = keyColumns.isEmpty() ? null : keyColumns.get(0);
    }

    public void insert(Row row) {
        row = normalize(row.getData());
        validate(row);
        if (primaryKey != null) {
            Object key = requireKey(row);
            if (keys.contains(key)) throw duplicateKey(key);
            keys.add(key);
        }
        rows.add(row);
    }

    public int delete(Predicate<Row> filter) {
        int before = rows.size();
        rows.removeIf(row -> {
            if (!filter.test(row)) return false;
            if (primaryKey != null) keys.remove(row.get(primaryKey.getName()));
            return true;
        });
        return before - rows.size();
    }

    public int update(Predicate<Row> filter, Map<String, Object> values) {
        // Existing cells are already valid, so checking the new values is enough
        Row changes = normalize(values);
        validate(changes);

        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            if (filter.test(rows.get(i))) matches.add(i);
        }

        // Check the primary key before changing anything, so a failed update leaves the table as it was
        if (primaryKey != null && changes.getData().containsKey(primaryKey.getName())) {
            Object newKey = requireKey(changes);
            if (matches.size() > 1) {
                throw new IllegalArgumentException("Cannot set primary key '" + primaryKey.getName()
                        + "' to the same value on " + matches.size() + " rows");
            }
            if (matches.size() == 1) {
                Object oldKey = rows.get(matches.get(0)).get(primaryKey.getName());
                if (!newKey.equals(oldKey) && keys.contains(newKey)) throw duplicateKey(newKey);
                keys.remove(oldKey);
                keys.add(newKey);
            }
        }

        for (int i : matches) {
            Map<String, Object> data = new LinkedHashMap<>(rows.get(i).getData());
            data.putAll(changes.getData());
            rows.set(i, normalize(data));
        }
        return matches.size();
    }

    public Column getPrimaryKey() {
        return primaryKey;
    }

    public List<Row> getRows() {
        return Collections.unmodifiableList(rows);
    }

    public List<Column> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    public String getName() {
        return name;
    }

    private Object requireKey(Row row) {
        Object key = row.get(primaryKey.getName());
        if (key == null) throw new IllegalArgumentException("Primary key '" + primaryKey.getName() + "' cannot be empty");
        return key;
    }

    private IllegalArgumentException duplicateKey(Object key) {
        return new IllegalArgumentException("Duplicate primary key: " + primaryKey.getName() + "=" + key);
    }

    // Puts values in column order and widens whole numbers for FLOAT columns; unknown columns go last
    // so validate can report them
    private Row normalize(Map<String, Object> values) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (Column col : columns) {
            if (!values.containsKey(col.getName())) continue;
            Object value = values.get(col.getName());
            if (col.getDataType() == DataType.FLOAT && value instanceof Integer i) value = i.floatValue();
            data.put(col.getName(), value);
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            data.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new Row(data);
    }

    private void validate(Row row) {
        for (String name : row.getData().keySet()) {
            if (columns.stream().noneMatch(col -> col.getName().equals(name))) {
                throw new IllegalArgumentException("Unknown column '" + name + "'");
            }
        }
        for (Column col : columns) {
            Object value = row.get(col.getName());
            if (value == null) continue;

            boolean valid = switch (col.getDataType()) {
                case INTEGER -> value instanceof Integer;
                case VARCHAR -> value instanceof String;
                case BOOLEAN -> value instanceof Boolean;
                case FLOAT -> value instanceof Float;
            };

            if (!valid) {
                throw new IllegalArgumentException("Column '" + col.getName() + "' expects " + col.getDataType());
            }
        }
    }
}