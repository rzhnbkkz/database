import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RowTest {

    @Test
    void getReturnsValueOrNullForUnknownColumn() {
        Row row = new Row(Map.of("id", 1));
        assertEquals(1, row.get("id"));
        assertNull(row.get("missing"));
    }

    @Test
    void rowCopiesTheMapPassedToConstructor() {
        Map<String, Object> values = new HashMap<>(Map.of("id", 1));
        Row row = new Row(values);
        values.put("id", 2);

        assertEquals(1, row.get("id"));
    }

    @Test
    void getDataIsReadOnly() {
        Row row = new Row(Map.of("id", 1));
        assertThrows(UnsupportedOperationException.class, () -> row.getData().put("id", 2));
    }

    @Test
    void toStringShowsValuesInInsertionOrder() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", 1);
        values.put("name", "Alice");
        assertEquals("{id=1, name=Alice}", new Row(values).toString());
    }
}
