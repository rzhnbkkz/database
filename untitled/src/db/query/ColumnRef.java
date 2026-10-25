package db.query;

import db.model.DataType;
import db.model.Row;

import java.util.List;

// A column as written in a query: name, or table.name (table may be an alias). In a select list,
// table.* is a ColumnRef whose column is "*". Once resolved by a Scope, key() is the row key to read.
public record ColumnRef(String table, String column) implements SelectItem {

    public ColumnRef(String column) {
        this(null, column);
    }

    public boolean isStar() {
        return table != null && column.equals("*");
    }

    public String key() {
        return table == null ? column : table + "." + column;
    }

    public ColumnRef resolve(Scope scope) {
        return scope.resolve(this);
    }

    public Object evaluate(Row row) {
        return row.get(key());
    }

    public DataType type(Scope scope) {
        return scope.column(this).getDataType();
    }

    public List<SelectItem> children() {
        return List.of();
    }

    @Override
    public String toString() {
        return key();
    }
}
