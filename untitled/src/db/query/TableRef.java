package db.query;

// A table in FROM or JOIN; alias is the name queries use for it, the table name when none was given
public record TableRef(String table, String alias) {

    public TableRef(String table) {
        this(table, table);
    }

    @Override
    public String toString() {
        return table.equals(alias) ? table : table + " " + alias;
    }
}
