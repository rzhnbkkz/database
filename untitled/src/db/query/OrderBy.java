package db.query;

// One ORDER BY item: the column to sort on, and whether biggest comes first
public record OrderBy(ColumnRef column, boolean descending) {

    public OrderBy resolve(Scope scope) {
        return new OrderBy(scope.resolve(column), descending);
    }

    @Override
    public String toString() {
        return column + (descending ? " DESC" : "");
    }
}
