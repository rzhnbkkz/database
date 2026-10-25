package db.query;

// Something a SELECT list or ORDER BY can name: a column (or table.*), or an aggregate like COUNT(*)
public sealed interface SelectItem permits ColumnRef, Aggregate {
}
