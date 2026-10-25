package db.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

// Sorted index on one or more columns, like MySQL's default B-tree index: (city, age) -> ids of the rows
// holding those values, ordered by city, then age. A lookup gives equal values for the first columns and
// optionally a range on the next one, so (city, age) answers city = 'Oslo' AND age > 30, but not age > 30
// alone. Empty cells are kept, and sort before every value, so city = 'Oslo' also finds Oslo rows without
// an age. Ids come back sorted (table order) unless asked for in index order.
public class Index {
    private final String name;
    private final List<String> columns;
    private final List<DataType> types;
    private final boolean unique;
    private final TreeMap<List<Object>, SortedSet<Long>> entries = new TreeMap<>(Index::compareKeys);

    // Bound markers, below and above every value (including empty): a bound is a partial key plus one of
    // these, so it falls just before or just after all the keys that start with that part
    private static final Object LOW = new Object();
    private static final Object HIGH = new Object();

    // Which keys a lookup wants: equal values for the first prefix.size() columns, then when ranged a range
    // on the next column. A null end of the range is open, but empty cells are never in a range.
    public record Slice(List<Object> prefix, boolean ranged, Object low, boolean lowInclusive, Object high, boolean highInclusive) {
        public static final Slice ALL = new Slice(List.of(), false, null, false, null, false);

        public static Slice equal(List<Object> prefix) {
            return new Slice(prefix, false, null, false, null, false);
        }

        public static Slice range(List<Object> prefix, Object low, boolean lowInclusive, Object high, boolean highInclusive) {
            return new Slice(prefix, true, low, lowInclusive, high, highInclusive);
        }
    }

    public Index(String name, List<String> columns, List<DataType> types, boolean unique) {
        this.name = name;
        this.columns = List.copyOf(columns);
        this.types = List.copyOf(types);
        this.unique = unique;
    }

    public String getName() {
        return name;
    }

    public List<String> getColumns() {
        return columns;
    }

    public boolean isUnique() {
        return unique;
    }

    void add(long id, Row row) {
        entries.computeIfAbsent(keyOf(row), k -> new TreeSet<>()).add(id);
    }

    void remove(long id, Row row) {
        List<Object> key = keyOf(row);
        SortedSet<Long> ids = entries.get(key);
        if (ids == null) return;
        ids.remove(id);
        if (ids.isEmpty()) entries.remove(key);
    }

    // The row's values for the indexed columns, numbers as Double so 2 and 2.0 are the same key, like in WHERE
    List<Object> keyOf(Row row) {
        List<Object> key = new ArrayList<>();
        for (String column : columns) key.add(normalize(row.get(column)));
        return key;
    }

    // "city=Oslo, age=30", for duplicate-value errors
    String describe(Row row) {
        return columns.stream().map(column -> column + "=" + row.get(column)).collect(Collectors.joining(", "));
    }

    // Ids of rows whose single indexed column, or first column, equals value
    public SortedSet<Long> lookup(Object value) {
        return ids(Slice.equal(Collections.singletonList(value)));
    }

    // Ids of rows whose first column is in the range, using WHERE's rules for < <= > >= (null = open end)
    public SortedSet<Long> range(Object low, boolean lowInclusive, Object high, boolean highInclusive) {
        return ids(Slice.range(List.of(), low, lowInclusive, high, highInclusive));
    }

    // Ids of the rows in the slice, in table order
    public SortedSet<Long> ids(Slice slice) {
        NavigableMap<List<Object>, SortedSet<Long>> part = part(slice);
        if (part == null) return Collections.emptySortedSet();
        Iterator<SortedSet<Long>> values = part.values().iterator();
        if (!values.hasNext()) return Collections.emptySortedSet();
        SortedSet<Long> first = values.next();
        // One key (the usual = lookup): no need to copy
        if (!values.hasNext()) return Collections.unmodifiableSortedSet(first);
        SortedSet<Long> ids = new TreeSet<>(first);
        while (values.hasNext()) ids.addAll(values.next());
        return ids;
    }

    // Ids of the rows in the slice in index order (empty cells first), read lazily so a query with LIMIT
    // can stop early. Rows with equal keys stay in table order, also when descending, which is how a
    // stable sort would leave them.
    public Iterator<Long> inOrder(Slice slice, boolean descending) {
        NavigableMap<List<Object>, SortedSet<Long>> part = part(slice);
        if (part == null) return Collections.emptyIterator();
        Iterator<SortedSet<Long>> keys = (descending ? part.descendingMap() : part).values().iterator();
        return new Iterator<>() {
            private Iterator<Long> ids = Collections.emptyIterator();

            public boolean hasNext() {
                while (!ids.hasNext() && keys.hasNext()) ids = keys.next().iterator();
                return ids.hasNext();
            }

            public Long next() {
                if (!hasNext()) throw new NoSuchElementException();
                return ids.next();
            }
        };
    }

    // The part of the index a slice covers, or null when it can't match anything: an empty value (unknown,
    // as in WHERE), a value of the wrong kind for its column (text against numbers), or a backwards range
    private NavigableMap<List<Object>, SortedSet<Long>> part(Slice slice) {
        List<Object> prefix = new ArrayList<>();
        for (int i = 0; i < slice.prefix().size(); i++) {
            Object value = slice.prefix().get(i);
            if (value == null || !fits(value, types.get(i))) return null;
            prefix.add(normalize(value));
        }
        List<Object> from = new ArrayList<>(prefix), to = new ArrayList<>(prefix);
        if (!slice.ranged()) {
            from.add(LOW);
            to.add(HIGH);
        } else {
            DataType type = types.get(prefix.size());
            if ((slice.low() != null && !fits(slice.low(), type)) || (slice.high() != null && !fits(slice.high(), type))) return null;
            // Open low end: start just after the empty cells, which sort first
            from.add(slice.low() == null ? null : normalize(slice.low()));
            from.add(slice.low() == null || !slice.lowInclusive() ? HIGH : LOW);
            if (slice.high() == null) {
                to.add(HIGH);
            } else {
                to.add(normalize(slice.high()));
                to.add(slice.highInclusive() ? HIGH : LOW);
            }
        }
        if (compareKeys(from, to) > 0) return null;
        // Bounds end in LOW or HIGH, so they never equal a stored key and inclusive is just a formality
        return entries.subMap(from, true, to, true);
    }

    private static boolean fits(Object value, DataType type) {
        return switch (type) {
            case INTEGER, FLOAT -> value instanceof Number;
            case VARCHAR -> value instanceof String;
            case BOOLEAN -> value instanceof Boolean;
        };
    }

    private static Object normalize(Object value) {
        return value instanceof Number n ? (Object) n.doubleValue() : value;
    }

    // Column by column. Stored keys all have one element per column; a bound may be one element longer, and
    // that last LOW or HIGH puts it before or after every key that matches it up to there.
    private static int compareKeys(List<Object> a, List<Object> b) {
        for (int i = 0; ; i++) {
            if (i == a.size() || i == b.size()) {
                if (a.size() == b.size()) return 0;
                List<Object> longer = a.size() > b.size() ? a : b;
                int longerSign = longer.get(i) == LOW ? -1 : 1;
                return longer == a ? longerSign : -longerSign;
            }
            int cmp = compareValues(a.get(i), b.get(i));
            if (cmp != 0) return cmp;
        }
    }

    // LOW < empty < values < HIGH. Values within one column are all Double, String or Boolean, whose
    // natural order is the order WHERE uses.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compareValues(Object a, Object b) {
        int rankA = rank(a), rankB = rank(b);
        if (rankA != rankB) return Integer.compare(rankA, rankB);
        return rankA == 2 ? ((Comparable) a).compareTo(b) : 0;
    }

    private static int rank(Object value) {
        if (value == LOW) return 0;
        if (value == null) return 1;
        if (value == HIGH) return 3;
        return 2;
    }
}
