package db.query;

// A column as written in a query: name, or table.name (table may be an alias). In a select list,
// table.* is a ColumnRef whose column is "*". Once resolved by a Scope, key() is the row key to read.
public record ColumnRef(String table, String column) {

    public ColumnRef(String column) {
        this(null, column);
    }

    public boolean isStar() {
        return table != null && column.equals("*");
    }

    public String key() {
        return table == null ? column : table + "." + column;
    }

    @Override
    public String toString() {
        return key();
    }
}
