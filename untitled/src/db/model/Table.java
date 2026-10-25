package db.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
        if (primaryKey != null) {
            indexes.put(PRIMARY, new Index(PRIMARY, List.of(primaryKey.getName()), List.of(primaryKey.getDataType()), true));
        }
    }

    public void insert(Row row) {
        row = normalize(row.getData());
        validate(row);
        if (primaryKey != null) {
            Object key = requireKey(row);
            if (!indexes.get(PRIMARY).lookup(key).isEmpty()) throw duplicateKey(key);
        }
        long id = nextId++;
        checkUnique(Map.of(id, row), true);
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

        Map<Long, Row> updated = new LinkedHashMap<>();
        for (long id : matches) {
            Map<String, Object> data = new LinkedHashMap<>(rows.get(id).getData());
            data.putAll(changes.getData());
            updated.put(id, normalize(data));
        }
        checkUnique(updated, false);

        for (Map.Entry<Long, Row> entry : updated.entrySet()) {
            Row old = rows.get(entry.getKey());
            for (Index index : indexes.values()) {
                index.remove(entry.getKey(), old);
                index.add(entry.getKey(), entry.getValue());
            }
            rows.put(entry.getKey(), entry.getValue());
        }
        return matches.size();
    }

    // UNIQUE indexes other than PRIMARY (whose checks above give their own messages): the new or changed
    // rows must not share values with each other or with the rest of the table. Like MySQL, a row with an
    // empty cell in the index never conflicts.
    private void checkUnique(Map<Long, Row> changed, boolean inserting) {
        for (Index index : indexes.values()) {
            if (!index.isUnique() || index.getName().equals(PRIMARY)) continue;
            Map<List<Object>, Long> seen = new HashMap<>();
            for (Map.Entry<Long, Row> entry : changed.entrySet()) {
                List<Object> key = index.keyOf(entry.getValue());
                if (key.contains(null)) continue;
                if (seen.put(key, entry.getKey()) != null) throw duplicateValue(index, entry.getValue());
                for (long other : index.ids(Index.Slice.equal(key))) {
                    if (inserting || !changed.containsKey(other)) throw duplicateValue(index, entry.getValue());
                }
            }
        }
    }

    private static IllegalArgumentException duplicateValue(Index index, Row row) {
        return new IllegalArgumentException("Duplicate value for unique index '" + index.getName() + "': " + index.describe(row));
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
        createIndex(indexName, List.of(column), false);
    }

    // A multi-column index is sorted by the first column, then the second, and so on
    public void createIndex(String indexName, List<String> indexColumns, boolean unique) {
        if (indexName.equalsIgnoreCase(PRIMARY)) {
            throw new IllegalArgumentException("The name " + PRIMARY + " is reserved for the primary key index");
        }
        if (indexes.containsKey(indexName)) {
            throw new IllegalArgumentException("Index '" + indexName + "' already exists on table '" + name + "'");
        }
        if (indexColumns.isEmpty()) throw new IllegalArgumentException("An index needs at least one column");
        List<DataType> types = new ArrayList<>();
        for (String column : indexColumns) {
            Column col = columns.stream().filter(c -> c.getName().equals(column)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown column '" + column + "'"));
            types.add(col.getDataType());
        }
        if (new HashSet<>(indexColumns).size() != indexColumns.size()) {
            throw new IllegalArgumentException("A column is listed twice in index '" + indexName + "'");
        }
        for (Index existing : indexes.values()) {
            if (existing.getColumns().equals(indexColumns)) {
                throw new IllegalArgumentException("Index '" + existing.getName() + "' already covers ("
                        + String.join(", ", indexColumns) + ")");
            }
        }
        Index index = new Index(indexName, indexColumns, types, unique);
        for (Map.Entry<Long, Row> entry : rows.entrySet()) index.add(entry.getKey(), entry.getValue());
        if (unique) {
            // Existing rows must already be unique
            Map<List<Object>, Row> seen = new HashMap<>();
            for (Row row : rows.values()) {
                List<Object> key = index.keyOf(row);
                if (!key.contains(null) && seen.put(key, row) != null) {
                    throw new IllegalArgumentException("Cannot create unique index '" + indexName
                            + "', two rows have " + index.describe(row));
                }
            }
        }
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

    // The index on exactly this one column, or null
    public Index getIndexOn(String column) {
        for (Index index : indexes.values()) {
            if (index.getColumns().equals(List.of(column))) return index;
        }
        return null;
    }

    // The row with this id, or null if it was deleted
    public Row getRow(long id) {
        return rows.get(id);
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