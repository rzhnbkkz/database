package db.query;

// Rebuilds a query's tree with some parts swapped: every column and every nested subquery's query passes
// through it. Subqueries use it to put an outer row's values in place of the outer columns they use.
public interface Rewriter {
    SelectItem column(ColumnRef column);

    SelectQuery query(SelectQuery query);
}
