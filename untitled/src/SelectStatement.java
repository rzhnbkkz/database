public class SelectStatement implements Statement{
    private String tableName;
    private Condition condition;

    public SelectStatement(String tableName, Condition condition) {
        this.tableName = tableName;
        this.condition = condition;
    }

    public SelectStatement(String tableName) {
        this(tableName, null);
    }

    public Condition getCondition() {
        return condition;
    }

    public String getTableName() {
        return tableName;
    }

    public void execute(QueryEngine query) {
        if(condition == null) {
            System.out.println(query.selectAll(query.getTable(tableName)));
        }
        else System.out.println(query.selectWhere(query.getTable(tableName), condition.getColumn(), condition.getValue()));
    }
}
