package db.query;

// One ORDER BY item: a column or an aggregate to sort on, and whether biggest comes first
public record OrderBy(SelectItem item, boolean descending) {

    public OrderBy resolve(Scope scope) {
        return new OrderBy(item instanceof Aggregate aggregate ? aggregate.resolve(scope) : scope.resolve((ColumnRef) item),
                descending);
    }

    @Override
    public String toString() {
        return item + (descending ? " DESC" : "");
    }
}
