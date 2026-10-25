package db.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class Table {
    // Name of the index every table with a primary key gets, as in MySQL
    public static final String PRIMARY = "PRIMARY";

    private final String name;
    private final List<Column> columns;
    // Rows by id, in insertion order. Ids never change, so indexes can point at rows.
    private final Map<Long, Row> rows = new LinkedHashMap<>();
    private long nextId = 0;
    private final Column primaryKey;
    private final Map<String, Index> indexes = new LinkedHashMap<>();

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = new ArrayList<>(columns);
        List<Column> keyColumns = columns.stream().filter(Column::isPrimaryKey).toList();
        if (keyColumns.size() > 1) throw new IllegalArgumentException("A table can have only one primary key");
        this.primaryKey = keyColumns.isEmpty() ? null : keyColumns.get(0);
        // The primary key index also makes the uniqueness check fast
        if (primaryKey != null) indexes.put(PRIMARY, new Index(PRIMARY, primaryKey.getName()));
    }

    public void insert(Row row) {
        row = normalize(row.getData());
        validate(row);
        if (primaryKey != null) {
            Object key = requireKey(row);
            if (!indexes.get(PRIMARY).lookup(key).isEmpty()) throw duplicateKey(key);
        }
        long id = nextId++;
        rows.put(id, row);
        for (Index index : indexes.values()) index.add(id, row);
    }

    public int delete(Predicate<Row> filter) {
        return delete(null, filter);
    }

    // Only the rows with these ids are tested (every row when null), so an index can narrow the work
    public int delete(Collection<Long> candidates, Predicate<Row> filter) {
        List<Long> matches = matching(candidates, filter);
        for (long id : matches) {
            Row row = rows.remove(id);
            for (Index index : indexes.values()) index.remove(id, row);
        }
        return matches.size();
    }

    public int update(Predicate<Row> filter, Map<String, Object> values) {
        return update(null, filter, values);
    }

    public int update(Collection<Long> candidates, Predicate<Row> filter, Map<String, Object> values) {
        // Existing cells are already valid, so checking the new values is enough
        Row changes = normalize(values);
        validate(changes);

        List<Long> matches = matching(candidates, filter);

        // Check the primary key before changing anything, so a failed update leaves the table as it was
        if (primaryKey != null && changes.getData().containsKey(primaryKey.getName())) {
            Object newKey = requireKey(changes);
            if (matches.size() > 1) {
                throw new IllegalArgumentException("Cannot set primary key '" + primaryKey.getName()
                        + "' to the same value on " + matches.size() + " rows");
            }
            if (matches.size() == 1) {
                for (long other : indexes.get(PRIMARY).lookup(newKey)) {
                    if (other != matches.get(0)) throw duplicateKey(newKey);
                }
            }
        }

        for (long id : matches) {
            Row old = rows.get(id);
            Map<String, Object> data = new LinkedHashMap<>(old.getData());
            data.putAll(changes.getData());
            Row updated = normalize(data);
            for (Index index : indexes.values()) {
                index.remove(id, old);
                index.add(id, updated);
            }
            rows.put(id, updated);
        }
        return matches.size();
    }

    // Ids of the rows that pass the filter, in table order, collected before anything changes
    private List<Long> matching(Collection<Long> candidates, Predicate<Row> filter) {
        List<Long> matches = new ArrayList<>();
        for (long id : candidates == null ? rows.keySet() : candidates) {
            Row row = rows.get(id);
            if (row != null && filter.test(row)) matches.add(id);
        }
        return matches;
    }

    public void createIndex(String indexName, String column) {
        if (indexName.equalsIgnoreCase(PRIMARY)) {
            throw new IllegalArgumentException("The name " + PRIMARY + " is reserved for the primary key index");
        }
        if (indexes.containsKey(indexName)) {
            throw new IllegalArgumentException("Index '" + indexName + "' already exists on table '" + name + "'");
        }
        if (columns.stream().noneMatch(col -> col.getName().equals(column))) {
            throw new IllegalArgumentException("Unknown column '" + column + "'");
        }
        Index existing = getIndexOn(column);
        if (existing != null) {
            throw new IllegalArgumentException("Column '" + column + "' already has index '" + existing.getName() + "'");
        }
        Index index = new Index(indexName, column);
        for (Map.Entry<Long, Row> entry : rows.entrySet()) index.add(entry.getKey(), entry.getValue());
        indexes.put(indexName, index);
    }

    public void dropIndex(String indexName) {
        if (indexName.equals(PRIMARY)) {
            throw new IllegalArgumentException("Cannot drop the " + PRIMARY + " index, it belongs to the primary key");
        }
        if (indexes.remove(indexName) == null) {
            throw new IllegalArgumentException("Index '" + indexName + "' does not exist on table '" + name + "'");
        }
    }

    public List<Index> getIndexes() {
        return List.copyOf(indexes.values());
    }

    // The index on this column, or null
    public Index getIndexOn(String column) {
        for (Index index : indexes.values()) {
            if (index.getColumn().equals(column)) return index;
        }
        return null;
    }

    public Column getPrimaryKey() {
        return primaryKey;
    }

    public List<Row> getRows() {
        return Collections.unmodifiableList(new ArrayList<>(rows.values()));
    }

    // The rows with these ids, in the order given; ids of deleted rows are skipped
    public List<Row> getRows(Collection<Long> ids) {
        List<Row> result = new ArrayList<>();
        for (long id : ids) {
            Row row = rows.get(id);
            if (row != null) result.add(row);
        }
        return result;
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