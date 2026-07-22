import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class Row {
    private final Map<String, Object> data;

    public Row(Map<String, Object> data) {
        this.data = new LinkedHashMap<>(data);
    }

    public Object get(String columnName) {
        return data.get(columnName);
    }

    public Map<String, Object> getData() {
        return Collections.unmodifiableMap(data);
    }

    @Override
    public String toString() {
        return data.toString();
    }
}
