public class Condition {
    private String column;
    private Object value;

    public Condition(String column, Object value) {
        this.column = column;
        this.value = value;
    }

    public String getColumn() {
        return column;
    }

    public Object getValue() {
        return value;
    }

    public boolean matches(Row row) {
        if(row.get(column) == null) return false;
        return row.get(column).equals(value);
    }
}
