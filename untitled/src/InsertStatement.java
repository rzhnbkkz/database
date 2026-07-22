public class InsertStatement implements Statement{
    private String tableName;
    private Row row;

    public InsertStatement(String tableName, Row row) {
        this.tableName = tableName;
        this.row = row;
    }

    public String getTableName() {
        return tableName;
    }

    public Row getRow() {
        return row;
    }

    public void execute(QueryEngine query) {
        query.insert(query.getTable(tableName), row.getData());
    }
}
