package db.query;

// Something a SELECT list or ORDER BY can name: a column (or table.*), or an aggregate like COUNT(*)
public sealed interface SelectItem permits ColumnRef, Aggregate {
    // Once resolved, the key its value is stored under in a row: u.id, or COUNT(*) in a group row
    String key();

    default SelectItem resolve(Scope scope) {
        return this instanceof Aggregate aggregate ? aggregate.resolve(scope) : scope.resolve((ColumnRef) this);
    }
}
