package db.query;

import db.model.Index;
import db.model.Row;
import db.model.Table;

import java.util.Collection;
import java.util.Set;

// Reading a table through an index instead of scanning it: the rows whose indexed column equals
// value. value is a literal, or a column of a table read earlier in the join (u.id in o.user_id = u.id).
public record IndexLookup(Index index, Condition condition, Object value) {

    // Finds a condition the index can answer: column = value, on its own or inside AND (under OR or NOT,
    // an index can't rule rows out). alias is how the table's columns are named in the expression;
    // earlier holds the aliases of tables whose values are known when this table is read. null if none.
    public static IndexLookup find(Table table, String alias, boolean qualified, Expression expression, Set<String> earlier) {
        if (expression instanceof And and) {
            for (Expression part : and.parts()) {
                IndexLookup lookup = find(table, alias, qualified, part, earlier);
                if (lookup != null) return lookup;
            }
            return null;
        }
        if (!(expression instanceof Condition condition) || condition.getOperator() != Condition.Operator.EQUALS) {
            return null;
        }
        // Aggregates only appear in HAVING, which runs after the rows are read
        if (!(condition.getLeft() instanceof ColumnRef left) || condition.getValue() instanceof Aggregate) return null;
        // Either side may be this table's column: u.id = o.user_id and o.user_id = u.id both work
        IndexLookup lookup = tryColumn(table, alias, qualified, condition, left, condition.getValue(), earlier);
        if (lookup == null && condition.getValue() instanceof ColumnRef right) {
            lookup = tryColumn(table, alias, qualified, condition, right, left, earlier);
        }
        return lookup;
    }

    private static IndexLookup tryColumn(Table table, String alias, boolean qualified, Condition condition,
                                         ColumnRef column, Object other, Set<String> earlier) {
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
        return new IndexLookup(index, condition, other);
    }

    // Ids of the matching rows; left is the combined row so far, for values from earlier tables
    public Collection<Long> ids(Row left) {
        return index.lookup(value instanceof ColumnRef ref ? left.get(ref.key()) : value);
    }

    @Override
    public String toString() {
        return "index " + index.getName() + " (" + condition + ")";
    }
}
