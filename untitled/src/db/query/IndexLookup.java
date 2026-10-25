package db.query;

import db.model.Index;
import db.model.Row;
import db.model.Table;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Reading a table through an index instead of scanning it: the rows whose indexed column equals a value,
// or lies in a range (age >= 18 AND age < 30). Each end of the range is a literal, or a column of a table
// read earlier in the join (u.id in o.user_id = u.id). low or high is null when that end is open.
public record IndexLookup(Index index, List<Condition> conditions, Bound low, Bound high) {

    public record Bound(Object value, boolean inclusive) {
        Object valueFor(Row left) {
            return value instanceof ColumnRef ref ? left.get(ref.key()) : value;
        }
    }

    // A condition the index can answer, turned around if needed so the indexed column is on the left
    private record Usable(Condition condition, Index index, Condition.Operator operator, Object other) {}

    // Picks how to read the table: an equality if there is one, since it narrows the most, otherwise
    // a range on one index, with both ends when the conditions give them. Only conditions on their own
    // or inside AND count (under OR or NOT, an index can't rule rows out). alias is how the table's
    // columns are named in the expression; earlier holds the aliases of tables whose values are known
    // when this table is read. null if no index helps.
    public static IndexLookup find(Table table, String alias, boolean qualified, Expression expression, Set<String> earlier) {
        List<Usable> usable = new ArrayList<>();
        collect(table, alias, qualified, expression, earlier, usable);
        for (Usable u : usable) {
            if (u.operator() == Condition.Operator.EQUALS) {
                Bound exact = new Bound(u.other(), true);
                return new IndexLookup(u.index(), List.of(u.condition()), exact, exact);
            }
        }
        IndexLookup best = null;
        for (Usable start : usable) {
            Bound low = null, high = null;
            List<Condition> used = new ArrayList<>();
            // Any further bounds on the same column are still checked by WHERE or ON afterwards
            for (Usable u : usable) {
                if (u.index() != start.index()) continue;
                boolean lower = u.operator() == Condition.Operator.GREATER || u.operator() == Condition.Operator.GREATER_OR_EQUAL;
                boolean inclusive = u.operator() == Condition.Operator.GREATER_OR_EQUAL || u.operator() == Condition.Operator.LESS_OR_EQUAL;
                if (lower && low == null) {
                    low = new Bound(u.other(), inclusive);
                    used.add(u.condition());
                } else if (!lower && high == null) {
                    high = new Bound(u.other(), inclusive);
                    used.add(u.condition());
                }
            }
            boolean closed = low != null && high != null;
            if (best == null || (closed && (best.low() == null || best.high() == null))) {
                best = new IndexLookup(start.index(), used, low, high);
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
        // Aggregates only appear in HAVING, which runs after the rows are read
        if (!(expression instanceof Condition condition) || !(condition.getLeft() instanceof ColumnRef left)
                || condition.getValue() instanceof Aggregate || condition.getOperator() == Condition.Operator.NOT_EQUALS) {
            return;
        }
        // Either side may be this table's column: u.id < o.total is read as o.total > u.id
        Usable u = usable(table, alias, qualified, condition, left, condition.getOperator(), condition.getValue(), earlier);
        if (u == null && condition.getValue() instanceof ColumnRef right) {
            u = usable(table, alias, qualified, condition, right, flip(condition.getOperator()), left, earlier);
        }
        if (u != null) usable.add(u);
    }

    private static Usable usable(Table table, String alias, boolean qualified, Condition condition,
                                 ColumnRef column, Condition.Operator operator, Object other, Set<String> earlier) {
        boolean ours = qualified ? alias.equals(column.table()) : column.table() == null;
        if (!ours) return null;
        Index index = table.getIndexOn(column.column());
        if (index == null) return null;
        if (other instanceof ColumnRef ref) {
            // Comparing with this table's own column (id = manager_id) needs every row anyway
            if (ref.table() == null || !earlier.contains(ref.table())) return null;
        } else if (other == null) {
            return null;
        }
        return new Usable(condition, index, operator, other);
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

    // Ids of the matching rows; left is the combined row so far, for values from earlier tables
    public Collection<Long> ids(Row left) {
        Object lowValue = low == null ? null : low.valueFor(left);
        Object highValue = high == null ? null : high.valueFor(left);
        // Comparing with an empty cell matches nothing
        if ((low != null && lowValue == null) || (high != null && highValue == null)) return Collections.emptySortedSet();
        return index.range(lowValue, low == null || low.inclusive(), highValue, high == null || high.inclusive());
    }

    @Override
    public String toString() {
        return "index " + index.getName() + " (" + conditions.stream().map(Condition::toString).collect(Collectors.joining(" AND ")) + ")";
    }
}
