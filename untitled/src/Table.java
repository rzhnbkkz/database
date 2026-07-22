import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Table {
    private final String name;
    private final List<Column> columns;
    private final List<Row> rows;

    public Table(String name, List<Column> columns) {
        this.name = name;
        this.columns = new ArrayList<>(columns);
        this.rows = new ArrayList<>();
    }

    public void insert(Row row) {
        validate(row);
        rows.add(row);
    }

    public List<Row> getRows() {
        return Collections.unmodifiableList(rows);
    }

    public String getName() {
        return name;
    }

    private void validate(Row row) {
        for (Column col : columns) {
            Object value = row.get(col.getName());
            if (value == null) continue;

            boolean valid = switch (col.getDataType()) {
                case INTEGER -> value instanceof Integer;
                case VARCHAR -> value instanceof String;
                case BOOLEAN -> value instanceof Boolean;
                case FLOAT   -> value instanceof Float;
            };

            if (!valid) {
                throw new IllegalArgumentException(
                        "Column '" + col.getName() + "' expects " + col.getDataType()
                );
            }
        }
    }
}