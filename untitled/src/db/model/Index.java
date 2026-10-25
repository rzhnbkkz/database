package db.model;

import java.util.Collections;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

// Sorted index on one column: value -> ids of the rows holding it, kept in value order (like MySQL's
// default B-tree index) so both = and ranges like age >= 18 AND age < 30 can be read off it.
// Ids come back sorted, which is table order, so a lookup returns rows in the order a full scan would.
public class Index {
    private final String name;
    private final String column;
    private final TreeMap<Object, SortedSet<Long>> entries = new TreeMap<>(Index::compareKeys);

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
        return range(value, true, value, true);
    }

    // Ids of rows whose cell is between low and high, using the same rules as WHERE's < <= > >=.
    // A null end is open: range(18, true, null, false) is everything >= 18.
    public SortedSet<Long> range(Object low, boolean lowInclusive, Object high, boolean highInclusive) {
        Object lowKey = low == null ? null : key(low);
        Object highKey = high == null ? null : key(high);
        if (!sameKind(lowKey) || !sameKind(highKey)) return Collections.emptySortedSet();
        NavigableMap<Object, SortedSet<Long>> part = entries;
        if (lowKey != null && highKey != null) {
            if (compareKeys(lowKey, highKey) > 0) return Collections.emptySortedSet();
            part = entries.subMap(lowKey, lowInclusive, highKey, highInclusive);
        } else if (lowKey != null) {
            part = entries.tailMap(lowKey, lowInclusive);
        } else if (highKey != null) {
            part = entries.headMap(highKey, highInclusive);
        }
        Iterator<SortedSet<Long>> values = part.values().iterator();
        if (!values.hasNext()) return Collections.emptySortedSet();
        SortedSet<Long> first = values.next();
        // One value (the usual = lookup): no need to copy
        if (!values.hasNext()) return Collections.unmodifiableSortedSet(first);
        SortedSet<Long> ids = new TreeSet<>(first);
        while (values.hasNext()) ids.addAll(values.next());
        return ids;
    }

    // WHERE compares numbers by value (2 = 2.0), so the index must too
    private static Object key(Object value) {
        return value instanceof Number n ? (Object) n.doubleValue() : value;
    }

    // A column holds one kind of value, so a value of another kind (text against numbers) can't be
    // compared with the stored ones, and like in WHERE it matches nothing
    private boolean sameKind(Object key) {
        return key == null || entries.isEmpty() || entries.firstKey().getClass() == key.getClass();
    }

    // Keys are Double, String or Boolean, the same kind within one index, so their natural order
    // is the order WHERE uses
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareKeys(Object a, Object b) {
        return ((Comparable) a).compareTo(b);
    }
}
