package db.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class Table {
    // Name of the index every table with a primary key gets, as in MySQL
    public static final String PRIMARY = "PRIMARY";

    // The value DEFAULT in INSERT ... VALUES (DEFAULT) or UPDATE ... SET x = DEFAULT: the column's default
    public static final Object DEFAULT = new Object() {
        @Override
        public String toString() {
            return "DEFAULT";
        }
    };

    private String name;
    private final List<Column> columns = new ArrayList<>();
    // Rows by id, in insertion order. Ids never change, so indexes can point at rows.
    private final Map<Long, Row> rows = new LinkedHashMap<>();
    private long nextId = 0;
    private Column primaryKey;
    private final Map<String, Index> indexes = new LinkedHashMap<>();
    // The next AUTO_INCREMENT value. Like MySQL, numbers of deleted rows aren't given out again.
    private long nextAutoIncrement = 1;

    public Table(String name, List<Column> columns) {
        this.name = name;
        for (Column column : columns) this.columns.add(checked(column));
        List<Column> keyColumns = this.columns.stream().filter(Column::isPrimaryKey).toList();
        if (keyColumns.size() > 1) throw new IllegalArgumentException("A table can have only one primary key");
        this.primaryKey = keyColumns.isEmpty() ? null : keyColumns.get(0);
        // The primary key index also makes the uniqueness check fast
        if (primaryKey != null) {
            indexes.put(PRIMARY, new Index(PRIMARY, List.of(primaryKey.getName()), List.of(primaryKey.getDataType()), true));
        }
    }

    // Checks a column's options make sense together, and gives a FLOAT column's whole-number default as FLOAT
    private static Column checked(Column column) {
        if (column.isAutoIncrement()) {
            if (column.getDataType() != DataType.INTEGER) {
                throw new IllegalArgumentException("AUTO_INCREMENT column '" + column.getName() + "' must be INTEGER");
            }
            if (!column.isPrimaryKey()) {
                throw new IllegalArgumentException("AUTO_INCREMENT column '" + column.getName() + "' must be the PRIMARY KEY");
            }
            if (column.getDefaultValue() != null) {
                throw new IllegalArgumentException("AUTO_INCREMENT column '" + column.getName() + "' can't have a DEFAULT");
            }
        }
        Object value = column.getDefaultValue();
        if (value == null) return column;
        value = widen(column, value);
        if (!fits(column, value)) {
            throw new IllegalArgumentException("DEFAULT for column '" + column.getName() + "' must be " + column.getDataType());
        }
        return new Column(column.getName(), column.getDataType(), column.isPrimaryKey(), column.isNotNull(), value,
                column.isAutoIncrement());
    }

    public void insert(Row row) {
        insertAll(List.of(row.getData()));
    }

    // Inserts every row or, if any of them is invalid, none. A column a row leaves out gets its DEFAULT,
    // and an AUTO_INCREMENT column left out or NULL gets the next number. Returns the numbers given out.
    public List<Integer> insertAll(List<Map<String, Object>> newRows) {
        long next = nextAutoIncrement;
        long id = nextId;
        List<Integer> generated = new ArrayList<>();
        Map<Long, Row> added = new LinkedHashMap<>();
        Set<Object> newKeys = new HashSet<>();
        for (Map<String, Object> values : newRows) {
            Map<String, Object> data = new LinkedHashMap<>(values);
            for (Column column : columns) {
                if (column.isAutoIncrement() && data.get(column.getName()) == null) {
                    if (next > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("AUTO_INCREMENT column '" + column.getName() + "' has used up every INTEGER");
                    }
                    data.put(column.getName(), (int) next);
                    generated.add((int) next);
                    next++;
                } else if (!data.containsKey(column.getName()) && column.getDefaultValue() != null) {
                    data.put(column.getName(), column.getDefaultValue());
                }
            }
            Row row = normalize(data);
            validate(row, true);
            row = withoutEmptyCells(row);
            if (primaryKey != null) {
                Object key = requireKey(row);
                if (!indexes.get(PRIMARY).lookup(key).isEmpty() || !newKeys.add(key)) throw duplicateKey(key);
                // A number given by hand moves the counter past it, as in MySQL
                if (primaryKey.isAutoIncrement()) next = Math.max(next, (Integer) key + 1L);
            }
            added.put(id++, row);
        }
        checkUnique(added, true);

        for (Map.Entry<Long, Row> entry : added.entrySet()) {
            rows.put(entry.getKey(), entry.getValue());
            for (Index index : indexes.values()) index.add(entry.getKey(), entry.getValue());
        }
        nextId = id;
        nextAutoIncrement = next;
        return generated;
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
        // SET x = DEFAULT is the column's default, or empty
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Column column = column(entry.getKey());
            resolved.put(entry.getKey(), entry.getValue() == DEFAULT ? (column == null ? null : column.getDefaultValue()) : entry.getValue());
        }
        // Existing cells are already valid, so checking the new values is enough
        Row changes = normalize(resolved);
        validate(changes, false);

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
            updated.put(id, withoutEmptyCells(normalize(data)));
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
        // A key set by hand moves the AUTO_INCREMENT counter past it, so a later insert doesn't collide
        if (primaryKey != null && primaryKey.isAutoIncrement() && !matches.isEmpty()
                && changes.get(primaryKey.getName()) instanceof Integer key) {
            nextAutoIncrement = Math.max(nextAutoIncrement, key + 1L);
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

    // ALTER TABLE ... ADD COLUMN: existing rows get the column's DEFAULT, or stay empty
    public void addColumn(Column column) {
        if (column(column.getName()) != null) throw new IllegalArgumentException("Column '" + column.getName() + "' already exists");
        if (column.isPrimaryKey() || column.isAutoIncrement()) {
            throw new IllegalArgumentException("A PRIMARY KEY or AUTO_INCREMENT column can't be added to an existing table");
        }
        Column checked = checked(column);
        if (checked.isNotNull() && checked.getDefaultValue() == null && !rows.isEmpty()) {
            throw new IllegalArgumentException("Column '" + checked.getName() + "' is NOT NULL, so it needs a DEFAULT for the existing rows");
        }
        columns.add(checked);
        if (checked.getDefaultValue() != null) {
            rows.replaceAll((id, row) -> {
                Map<String, Object> data = new LinkedHashMap<>(row.getData());
                data.put(checked.getName(), checked.getDefaultValue());
                return new Row(data);
            });
        }
    }

    // ALTER TABLE ... DROP COLUMN. Indexes that use the column go too; returns their names.
    public List<String> dropColumn(String columnName) {
        Column column = requireColumn(columnName);
        if (column.isPrimaryKey()) throw new IllegalArgumentException("Can't drop the primary key column '" + columnName + "'");
        if (columns.size() == 1) throw new IllegalArgumentException("Can't drop the only column of table '" + name + "'");
        List<String> dropped = new ArrayList<>();
        indexes.values().removeIf(index -> {
            if (!index.getColumns().contains(columnName)) return false;
            dropped.add(index.getName());
            return true;
        });
        columns.remove(column);
        rows.replaceAll((id, row) -> {
            Map<String, Object> data = new LinkedHashMap<>(row.getData());
            data.remove(columnName);
            return new Row(data);
        });
        return dropped;
    }

    // ALTER TABLE ... RENAME COLUMN a TO b: the data, the primary key and indexes follow the new name
    public void renameColumn(String from, String to) {
        Column column = requireColumn(from);
        if (column(to) != null) throw new IllegalArgumentException("Column '" + to + "' already exists");
        Column renamed = column.renamed(to);
        columns.set(columns.indexOf(column), renamed);
        if (column == primaryKey) primaryKey = renamed;
        rows.replaceAll((id, row) -> {
            Map<String, Object> data = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.getData().entrySet()) {
                data.put(entry.getKey().equals(from) ? to : entry.getKey(), entry.getValue());
            }
            return new Row(data);
        });
        indexes.replaceAll((indexName, index) -> {
            if (!index.getColumns().contains(from)) return index;
            List<String> indexColumns = index.getColumns().stream().map(c -> c.equals(from) ? to : c).toList();
            List<DataType> types = indexColumns.stream().map(c -> column(c).getDataType()).toList();
            Index rebuilt = new Index(indexName, indexColumns, types, index.isUnique());
            for (Map.Entry<Long, Row> entry : rows.entrySet()) rebuilt.add(entry.getKey(), entry.getValue());
            return rebuilt;
        });
    }

    // ALTER TABLE ... RENAME TO; QueryEngine checks the new name is free
    public void rename(String newName) {
        this.name = newName;
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
        for (String column : indexColumns) types.add(requireColumn(column).getDataType());
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

    public long getNextAutoIncrement() {
        return nextAutoIncrement;
    }

    // For LOAD: the counter saved with the table, which may be past the biggest key if rows were deleted
    public void restoreNextAutoIncrement(long next) {
        nextAutoIncrement = Math.max(nextAutoIncrement, next);
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

    // The column with this name, or null
    private Column column(String columnName) {
        return columns.stream().filter(c -> c.getName().equals(columnName)).findFirst().orElse(null);
    }

    private Column requireColumn(String columnName) {
        Column column = column(columnName);
        if (column == null) throw new IllegalArgumentException("Unknown column '" + columnName + "'");
        return column;
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
            data.put(col.getName(), widen(col, values.get(col.getName())));
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            data.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new Row(data);
    }

    // Empty cells are stored as absent, so an explicit NULL and a column left out look the same
    private static Row withoutEmptyCells(Row row) {
        if (!row.getData().containsValue(null)) return row;
        Map<String, Object> data = new LinkedHashMap<>(row.getData());
        data.values().removeIf(value -> value == null);
        return new Row(data);
    }

    private static Object widen(Column column, Object value) {
        return column.getDataType() == DataType.FLOAT && value instanceof Integer i ? (Object) i.floatValue() : value;
    }

    private static boolean fits(Column column, Object value) {
        return switch (column.getDataType()) {
            case INTEGER -> value instanceof Integer;
            case VARCHAR -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
            case FLOAT -> value instanceof Float;
        };
    }

    // complete: the row is a whole new row, so a NOT NULL column it leaves out is an error too.
    // Otherwise (an UPDATE's changes) only the columns it sets are checked.
    private void validate(Row row, boolean complete) {
        for (String columnName : row.getData().keySet()) {
            if (column(columnName) == null) throw new IllegalArgumentException("Unknown column '" + columnName + "'");
        }
        for (Column col : columns) {
            Object value = row.get(col.getName());
            if (value == null) {
                if (col.isNotNull() && (complete || row.getData().containsKey(col.getName()))) {
                    throw new IllegalArgumentException("Column '" + col.getName() + "' cannot be empty");
                }
                continue;
            }
            if (!fits(col, value)) {
                throw new IllegalArgumentException("Column '" + col.getName() + "' expects " + col.getDataType());
            }
        }
    }
}
