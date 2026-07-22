import java.util.*;

public class QueryEngine {
    private HashMap<String, Table> tablesMap = new LinkedHashMap<>();

    public void addTable(Table table) {
        tablesMap.put(table.getName(), table);
    }

    public Table getTable(String tableName) {
        return tablesMap.get(tableName);
    }

    public static void insert(Table table, Map<String, Object> values) {
        Row row = new Row(values);
        table.insert(row);
    }

    public static List<Row> selectAll(Table table) {
        return new ArrayList<>(table.getRows());
    }

    public static List<Row> selectWhere(Table table, String column, Object value) {
        List<Row> result = new ArrayList<>();
        for (Row row : table.getRows()) {
            Object cell = row.get(column);
            if (cell != null && cell.equals(value)) {
                result.add(row);
            }
        }
        return result;
    }
}
