import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class Table {
    private final String name;
    private final List<Column> columns;
    private final List<Row> rows;

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = new ArrayList<>(columns);
        this.rows = new ArrayList<>();
    }

    public void insert(Row row) {
        row = normalize(row.getData());
        validate(row);
        rows.add(row);
    }

    public int delete(Predicate<Row> filter) {
        int before = rows.size();
        rows.removeIf(filter);
        return before - rows.size();
    }

    public int update(Predicate<Row> filter, Map<String, Object> values) {
        // Existing cells are already valid, so checking the new values is enough
        Row changes = normalize(values);
        validate(changes);
        int count = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (!filter.test(rows.get(i))) continue;
            Map<String, Object> data = new LinkedHashMap<>(rows.get(i).getData());
            data.putAll(changes.getData());
            rows.set(i, normalize(data));
            count++;
        }
        return count;
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