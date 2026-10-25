package db.query;

import db.model.DataType;
import db.model.Index;
import db.model.Row;
import db.model.Table;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Reading a table through an index instead of scanning it. Like MySQL, an index is used from its first
// column on: equal values for some leading columns (prefix), then optionally a range on the next one.
// With (city, age): city = 'Oslo' AND age > 30 uses both, age > 30 alone uses neither. Each value is a
// literal, or a column of a table read earlier in the join (u.id in o.user_id = u.id).
public record IndexLookup(Index index, List<Expression> conditions, List<Object> prefix, boolean ranged, Bound low, Bound high) {

    // One end of the range on the column after the prefix; null when that end is open
    public record Bound(Object value, boolean inclusive) {
    }

    // A condition an index could answer, as column operator value, turned around if needed so this table's
    // column is on the left. A LIKE with a fixed start gives two of these: name LIKE 'Al%' is name >= 'Al'
    // AND name < 'Am' (plus the LIKE itself, checked on the rows found).
    private record Usable(Expression condition, String column, Condition.Operator operator, Object other) {}

    // Picks the index that narrows the most: a unique index with every column matched by =, then the most
    // leading columns matched by =, then a range with both ends over one with a single end. Earlier indexes
    // (PRIMARY first) win ties. Only conditions on their own or inside AND count (under OR or NOT, an index
    // can't rule rows out). alias is how the table's columns are named in the expression; earlier holds the
    // aliases of tables whose values are known when this table is read. null if no index helps.
    public static IndexLookup find(Table table, String alias, boolean qualified, Expression expression, Set<String> earlier) {
        List<Usable> usable = new ArrayList<>();
        collect(table, alias, qualified, expression, earlier, usable);
        IndexLookup best = null;
        int bestScore = 0;
        for (Index index : table.getIndexes()) {
            List<Object> prefix = new ArrayList<>();
            List<Expression> used = new ArrayList<>();
            int matched = 0;
            for (String column : index.getColumns()) {
                Usable equal = usable.stream()
                        .filter(u -> u.column().equals(column) && u.operator() == Condition.Operator.EQUALS)
                        .findFirst().orElse(null);
                if (equal == null) break;
                prefix.add(equal.other());
                used.add(equal.condition());
                matched++;
            }
            Bound low = null, high = null;
            if (matched < index.getColumns().size()) {
                String next = index.getColumns().get(matched);
                // Any further bounds on the column are still checked by WHERE or ON afterwards
                for (Usable u : usable) {
                    if (!u.column().equals(next) || u.operator() == Condition.Operator.EQUALS) continue;
                    boolean lower = u.operator() == Condition.Operator.GREATER || u.operator() == Condition.Operator.GREATER_OR_EQUAL;
                    boolean inclusive = u.operator() == Condition.Operator.GREATER_OR_EQUAL || u.operator() == Condition.Operator.LESS_OR_EQUAL;
                    if (lower && low == null) {
                        low = new Bound(u.other(), inclusive);
                    } else if (!lower && high == null) {
                        high = new Bound(u.other(), inclusive);
                    } else {
                        continue;
                    }
                    if (!used.contains(u.condition())) used.add(u.condition());
                }
            }
            boolean ranged = low != null || high != null;
            if (matched == 0 && !ranged) continue;
            boolean exactUnique = index.isUnique() && matched == index.getColumns().size();
            int score = exactUnique ? 1_000_000 : matched * 10 + (low != null ? 1 : 0) + (high != null ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = new IndexLookup(index, used, prefix, ranged, low, high);
            }
        }
        return best;
    }

    private static void collect(Table table, String alias, boolean qualified, Expression expression, Set<String> earlier,
                                List<Usable> usable) {
        if (expression instanceof And and) {
            for (Expression part : and.parts()) collect(table, alias, qualified, part, earlier, usable);
            return;
        }
        if (expression instanceof Like like) {
            // Only for text columns: 42 LIKE '4%' matches as text, which a number index can't follow
            String start = like.literalPrefix();
            if (like.negated() || start.isEmpty() || !(like.item() instanceof ColumnRef column) || !ours(alias, qualified, column)
                    || table.getColumns().stream().noneMatch(c -> c.getName().equals(column.column()) && c.getDataType() == DataType.VARCHAR)) {
                return;
            }
            usable.add(new Usable(like, column.column(), Condition.Operator.GREATER_OR_EQUAL, start));
            char last = start.charAt(start.length() - 1);
            // Every text starting with "Al" is below "Am"
            if (last != Character.MAX_VALUE) {
                String after = start.substring(0, start.length() - 1) + (char) (last + 1);
                usable.add(new Usable(like, column.column(), Condition.Operator.LESS, after));
            }
            return;
        }
        if (!(expression instanceof Condition condition) || condition.getOperator() == Condition.Operator.NOT_EQUALS) return;
        // A literal on the left reads the other way round: 5 < age is age > 5
        if (condition.getLeft() instanceof Literal literal && condition.getValue() instanceof ColumnRef right) {
            Usable u = usable(alias, qualified, condition, right, flip(condition.getOperator()), literal.value(), earlier);
            if (u != null) usable.add(u);
            return;
        }
        // Otherwise an index needs a plain column compared with a value or another table's column; anything
        // computed (price * 2), and aggregates, which only appear in HAVING after the rows are read, can't use one
        if (!(condition.getLeft() instanceof ColumnRef left)
                || (condition.getValue() instanceof SelectItem item && !(item instanceof ColumnRef))) {
            return;
        }
        // Either side may be this table's column: u.id < o.total is read as o.total > u.id
        Usable u = usable(alias, qualified, condition, left, condition.getOperator(), condition.getValue(), earlier);
        if (u == null && condition.getValue() instanceof ColumnRef right) {
            u = usable(alias, qualified, condition, right, flip(condition.getOperator()), left, earlier);
        }
        if (u != null) usable.add(u);
    }

    private static Usable usable(String alias, boolean qualified, Condition condition, ColumnRef column,
                                 Condition.Operator operator, Object other, Set<String> earlier) {
        if (!ours(alias, qualified, column)) return null;
        if (other instanceof ColumnRef ref) {
            // Comparing with this table's own column (id = manager_id) needs every row anyway
            if (ref.table() == null || !earlier.contains(ref.table())) return null;
        } else if (other == null) {
            return null;
        }
        return new Usable(condition, column.column(), operator, other);
    }

    private static boolean ours(String alias, boolean qualified, ColumnRef column) {
        return qualified ? alias.equals(column.table()) : column.table() == null;
    }

    private static Condition.Operator flip(Condition.Operator operator) {
        return switch (operator) {
            case GREATER -> Condition.Operator.LESS;
            case LESS -> Condition.Operator.GREATER;
            case GREATER_OR_EQUAL -> Condition.Operator.LESS_OR_EQUAL;
            case LESS_OR_EQUAL -> Condition.Operator.GREATER_OR_EQUAL;
            default -> operator;
        };
    }

    // Ids of the matching rows in table order; left is the combined row so far, for values from earlier tables
    public Collection<Long> ids(Row left) {
        Index.Slice slice = slice(left);
        return slice == null ? Collections.emptySortedSet() : index.ids(slice);
    }

    // The same ids in index order, for an ORDER BY that this index already sorts
    public Iterator<Long> inOrder(Row left, boolean descending) {
        Index.Slice slice = slice(left);
        return slice == null ? Collections.emptyIterator() : index.inOrder(slice, descending);
    }

    // null when a value is empty: comparing with an empty cell matches nothing
    private Index.Slice slice(Row left) {
        List<Object> values = new ArrayList<>();
        for (Object value : prefix) {
            Object resolved = valueFor(value, left);
            if (resolved == null) return null;
            values.add(resolved);
        }
        if (!ranged) return Index.Slice.equal(values);
        Object lowValue = low == null ? null : valueFor(low.value(), left);
        Object highValue = high == null ? null : valueFor(high.value(), left);
        if ((low != null && lowValue == null) || (high != null && highValue == null)) return null;
        return Index.Slice.range(values, lowValue, low != null && low.inclusive(), highValue, high != null && high.inclusive());
    }

    private static Object valueFor(Object value, Row left) {
        return value instanceof ColumnRef ref ? left.get(ref.key()) : value;
    }

    @Override
    public String toString() {
        return "index " + index.getName() + " (" + conditions.stream().map(Expression::toString).collect(Collectors.joining(" AND ")) + ")";
    }
}
