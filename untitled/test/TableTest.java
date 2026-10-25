import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TableTest {
    private Table table;

    @BeforeEach
    void setUp() {
        table = new Table("users", List.of(
                new Column("id", DataType.INTEGER),
                new Column("name", DataType.VARCHAR),
                new Column("active", DataType.BOOLEAN),
                new Column("score", DataType.FLOAT)
        ));
    }

    @Test
    void newTableHasNameColumnsAndNoRows() {
        assertEquals("users", table.getName());
        assertEquals(4, table.getColumns().size());
        assertEquals("id", table.getColumns().get(0).getName());
        assertEquals(DataType.INTEGER, table.getColumns().get(0).getDataType());
        assertTrue(table.getRows().isEmpty());
    }

    @Test
    void insertAcceptsValuesOfEveryType() {
        table.insert(new Row(Map.of("id", 1, "name", "Alice", "active", true, "score", 1.5f)));

        assertEquals(1, table.getRows().size());
        Row row = table.getRows().get(0);
        assertEquals(1, row.get("id"));
        assertEquals("Alice", row.get("name"));
        assertEquals(true, row.get("active"));
        assertEquals(1.5f, row.get("score"));
    }

    @Test
    void insertKeepsRowsInInsertionOrder() {
        table.insert(new Row(Map.of("id", 1)));
        table.insert(new Row(Map.of("id", 2)));
        table.insert(new Row(Map.of("id", 3)));

        List<Object> ids = table.getRows().stream().map(r -> r.get("id")).toList();
        assertEquals(List.of(1, 2, 3), ids);
    }

    @Test
    void insertAllowsMissingAndNullValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("id", 1);
        values.put("name", null);
        table.insert(new Row(values));

        Row row = table.getRows().get(0);
        assertNull(row.get("name"));
        assertNull(row.get("active"));
    }

    @Test
    void insertRejectsWrongTypeForEachColumn() {
        assertThrows(IllegalArgumentException.class, () -> table.insert(new Row(Map.of("id", "one"))));
        assertThrows(IllegalArgumentException.class, () -> table.insert(new Row(Map.of("name", 5))));
        assertThrows(IllegalArgumentException.class, () -> table.insert(new Row(Map.of("active", "yes"))));
        assertThrows(IllegalArgumentException.class, () -> table.insert(new Row(Map.of("score", 1.5))));
        assertTrue(table.getRows().isEmpty(), "rejected rows must not be stored");
    }

    @Test
    void insertErrorNamesColumnAndExpectedType() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> table.insert(new Row(Map.of("id", "one"))));
        assertEquals("Column 'id' expects INTEGER", e.getMessage());
    }

    @Test
    void insertAcceptsWholeNumberForFloatColumn() {
        table.insert(new Row(Map.of("score", 7)));
        assertEquals(7.0f, table.getRows().get(0).get("score"));
    }

    @Test
    void updateAcceptsWholeNumberForFloatColumn() {
        table.insert(new Row(Map.of("id", 1)));
        table.update(row -> true, Map.of("score", 3));
        assertEquals(3.0f, table.getRows().get(0).get("score"));
    }

    @Test
    void insertedRowsKeepColumnOrder() {
        table.insert(new Row(Map.of("score", 1.5f, "name", "Alice", "id", 1, "active", true)));
        assertEquals("{id=1, name=Alice, active=true, score=1.5}", table.getRows().get(0).toString());
    }

    @Test
    void floatColumnStillRejectsDouble() {
        assertThrows(IllegalArgumentException.class, () -> table.insert(new Row(Map.of("score", 1.5))));
    }

    @Test
    void insertRejectsUnknownColumn() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> table.insert(new Row(Map.of("id", 1, "age", 30))));
        assertEquals("Unknown column 'age'", e.getMessage());
        assertTrue(table.getRows().isEmpty());
    }

    @Test
    void deleteRemovesMatchingRowsAndReturnsCount() {
        table.insert(new Row(Map.of("id", 1)));
        table.insert(new Row(Map.of("id", 2)));
        table.insert(new Row(Map.of("id", 3)));

        assertEquals(2, table.delete(row -> !row.get("id").equals(2)));
        assertEquals(List.of(2), table.getRows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void updateMergesValuesIntoMatchingRowsAndReturnsCount() {
        table.insert(new Row(Map.of("id", 1, "name", "Alice")));
        table.insert(new Row(Map.of("id", 2, "name", "Bob")));

        assertEquals(1, table.update(row -> row.get("id").equals(2), Map.of("name", "Robert", "active", true)));
        assertEquals("Alice", table.getRows().get(0).get("name"));
        assertEquals(Map.of("id", 2, "name", "Robert", "active", true), table.getRows().get(1).getData());
    }

    @Test
    void updateValidatesValuesEvenWhenNoRowMatches() {
        assertThrows(IllegalArgumentException.class, () -> table.update(row -> false, Map.of("id", "x")));
    }

    @Test
    void getRowsAndGetColumnsAreReadOnly() {
        assertThrows(UnsupportedOperationException.class, () -> table.getRows().add(new Row(Map.of())));
        assertThrows(UnsupportedOperationException.class,
                () -> table.getColumns().add(new Column("x", DataType.INTEGER)));
    }

    @Test
    void tableCopiesColumnListPassedToConstructor() {
        List<Column> columns = new ArrayList<>(List.of(new Column("id", DataType.INTEGER)));
        Table t = new Table("t", columns);
        columns.add(new Column("extra", DataType.VARCHAR));

        assertEquals(1, t.getColumns().size());
    }
}
