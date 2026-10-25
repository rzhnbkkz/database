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
        Table table = query.requireTable(tableName);
        if(condition == null) {
            System.out.println(query.selectAll(table));
        }
        else System.out.println(query.selectWhere(table, condition.getColumn(), condition.getValue()));
    }
}
