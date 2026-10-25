package db.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

// Hash index on one column: value -> ids of the rows holding it. Ids are kept sorted, which is
// table order, so a lookup returns rows in the same order a full scan would.
public class Index {
    private final String name;
    private final String column;
    private final Map<Object, SortedSet<Long>> entries = new HashMap<>();

    public Index(String name, String column) {
        this.name = name;
        this.column = column;
    }

    public String getName() {
        return name;
    }

    public String getColumn() {
        return column;
    }

    // Empty cells are left out: a comparison with an empty cell never matches anyway
    void add(long id, Row row) {
        Object value = row.get(column);
        if (value != null) entries.computeIfAbsent(key(value), k -> new TreeSet<>()).add(id);
    }

    void remove(long id, Row row) {
        Object value = row.get(column);
        if (value == null) return;
        SortedSet<Long> ids = entries.get(key(value));
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) entries.remove(key(value));
    }

    // Ids of rows whose cell equals value, using the same rules as WHERE column = value
    public SortedSet<Long> lookup(Object value) {
        if (value == null) return Collections.emptySortedSet();
        return Collections.unmodifiableSortedSet(entries.getOrDefault(key(value), Collections.emptySortedSet()));
    }

    // WHERE compares numbers by value (2 = 2.0), so the index must too
    private static Object key(Object value) {
        return value instanceof Number n ? (Object) n.doubleValue() : value;
    }
}
