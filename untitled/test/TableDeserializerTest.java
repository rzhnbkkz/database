import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TableDeserializerTest {

    private static Table usersTable() {
        Table t = new Table("users", List.of(
                new Column("id", DataType.INTEGER),
                new Column("name", DataType.VARCHAR),
                new Column("active", DataType.BOOLEAN),
                new Column("score", DataType.FLOAT)
        ));
        t.insert(new Row(Map.of("id", 1, "name", "Al\"ice\n\u00e9", "active", true, "score", 1.5f)));
        t.insert(new Row(Map.of("id", 2, "name", "Bob")));
        return t;
    }

    private static String singleColumn(String type, String value) {
        return "{\"name\": \"t\", \"columns\": [{\"name\": \"c\", \"type\": \"" + type + "\"}],"
                + " \"rows\": [{\"c\": " + value + "}]}";
    }

    @Test
    void readsNameColumnsAndRows() {
        String json = """
                {
                  "name": "users",
                  "columns": [
                    {"name": "id", "type": "INTEGER"},
                    {"name": "name", "type": "VARCHAR"},
                    {"name": "active", "type": "BOOLEAN"},
                    {"name": "score", "type": "FLOAT"}
                  ],
                  "rows": [
                    {"id": 1, "name": "Alice", "active": true, "score": 1.5}
                  ]
                }
                """;
        Table t = TableDeserializer.fromJSON(json);

        assertEquals("users", t.getName());
        assertEquals(List.of("id", "name", "active", "score"),
                t.getColumns().stream().map(Column::getName).toList());
        assertEquals(List.of(DataType.INTEGER, DataType.VARCHAR, DataType.BOOLEAN, DataType.FLOAT),
                t.getColumns().stream().map(Column::getDataType).toList());

        Row row = t.getRows().get(0);
        assertEquals(1, row.get("id"));
        assertEquals("Alice", row.get("name"));
        assertEquals(true, row.get("active"));
        assertEquals(1.5f, row.get("score"));
    }

    @Test
    void roundTripsThroughSerializer() {
        String json = TableSerializer.toJSON(usersTable());
        assertEquals(json, TableSerializer.toJSON(TableDeserializer.fromJSON(json)));
    }

    @Test
    void roundTripsEmptyTable() {
        String json = TableSerializer.toJSON(new Table("empty", List.of()));
        Table t = TableDeserializer.fromJSON(json);
        assertEquals("empty", t.getName());
        assertTrue(t.getColumns().isEmpty());
        assertTrue(t.getRows().isEmpty());
    }

    @Test
    void nullAndMissingValuesAreAbsent() {
        String json = """
                {"name": "t",
                 "columns": [{"name": "a", "type": "INTEGER"}, {"name": "b", "type": "INTEGER"}],
                 "rows": [{"a": null}]}
                """;
        Row row = TableDeserializer.fromJSON(json).getRows().get(0);
        assertTrue(row.getData().isEmpty());
    }

    @Test
    void rowsFieldIsOptional() {
        Table t = TableDeserializer.fromJSON("{\"name\": \"t\", \"columns\": []}");
        assertTrue(t.getRows().isEmpty());
    }

    @Test
    void ignoresUnknownFieldsAndColumnsNotInSchema() {
        String json = """
                {"name": "t", "version": 2,
                 "columns": [{"name": "a", "type": "INTEGER"}],
                 "rows": [{"a": 1, "extra": "ignored"}]}
                """;
        Row row = TableDeserializer.fromJSON(json).getRows().get(0);
        assertEquals(Map.of("a", 1), row.getData());
    }

    @Test
    void columnTypeIsCaseInsensitive() {
        Table t = TableDeserializer.fromJSON(singleColumn("varchar", "\"x\""));
        assertEquals(DataType.VARCHAR, t.getColumns().get(0).getDataType());
    }

    @Test
    void convertsNumbersToColumnType() {
        assertEquals(-42, TableDeserializer.fromJSON(singleColumn("INTEGER", "-42")).getRows().get(0).get("c"));
        assertEquals(10, TableDeserializer.fromJSON(singleColumn("INTEGER", "1e1")).getRows().get(0).get("c"));
        assertEquals(3.0f, TableDeserializer.fromJSON(singleColumn("FLOAT", "3")).getRows().get(0).get("c"));
        assertEquals(1.5e-3f, TableDeserializer.fromJSON(singleColumn("FLOAT", "1.5E-3")).getRows().get(0).get("c"));
    }

    @Test
    void decodesStringEscapes() {
        Object value = TableDeserializer.fromJSON(singleColumn("VARCHAR", "\"q\\\" b\\\\ s\\/ n\\n t\\t u\\u00e9\""))
                .getRows().get(0).get("c");
        assertEquals("q\" b\\ s/ n\n t\t u\u00e9", value);
    }

    @Test
    void rejectsValueOfWrongType() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TableDeserializer.fromJSON(singleColumn("INTEGER", "\"one\"")));
        assertEquals("Column 'c' expects INTEGER, got one", e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> TableDeserializer.fromJSON(singleColumn("VARCHAR", "5")));
        assertThrows(IllegalArgumentException.class, () -> TableDeserializer.fromJSON(singleColumn("BOOLEAN", "1")));
        assertThrows(IllegalArgumentException.class, () -> TableDeserializer.fromJSON(singleColumn("FLOAT", "true")));
    }

    @Test
    void rejectsNonWholeOrOutOfRangeInteger() {
        assertThrows(IllegalArgumentException.class, () -> TableDeserializer.fromJSON(singleColumn("INTEGER", "1.5")));
        assertThrows(IllegalArgumentException.class,
                () -> TableDeserializer.fromJSON(singleColumn("INTEGER", "3000000000")));
    }

    @Test
    void rejectsUnknownColumnType() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TableDeserializer.fromJSON(singleColumn("DATE", "null")));
        assertEquals("Unknown column type: DATE", e.getMessage());
    }

    @Test
    void rejectsMissingRequiredFields() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TableDeserializer.fromJSON("{\"columns\": []}"));
        assertEquals("Missing field: name", e.getMessage());

        assertThrows(IllegalArgumentException.class, () -> TableDeserializer.fromJSON("{\"name\": \"t\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> TableDeserializer.fromJSON("{\"name\": \"t\", \"columns\": [{\"name\": \"a\"}]}"));
    }

    @Test
    void rejectsWrongStructure() {
        assertThrows(IllegalArgumentException.class, () -> TableDeserializer.fromJSON("[]"));
        assertThrows(IllegalArgumentException.class, () -> TableDeserializer.fromJSON("{\"name\": 5, \"columns\": []}"));
        assertThrows(IllegalArgumentException.class,
                () -> TableDeserializer.fromJSON("{\"name\": \"t\", \"columns\": {}}"));
        assertThrows(IllegalArgumentException.class,
                () -> TableDeserializer.fromJSON("{\"name\": \"t\", \"columns\": [], \"rows\": [1]}"));
    }

    @Test
    void rejectsMalformedJson() {
        String[] inputs = {
                "",
                "{",
                "{\"name\": \"t\",}",
                "{\"name\" \"t\"}",
                "{name: \"t\"}",
                "{\"name\": \"t\"} extra",
                "{\"name\": \"unterminated}",
                "{\"name\": \"bad \\x escape\"}",
                "{\"name\": \"\\u12\"}",
                "{\"name\": tru}",
                "{\"name\": 1.2.3}",
        };
        for (String input : inputs) {
            assertThrows(IllegalArgumentException.class, () -> TableDeserializer.fromJSON(input), input);
        }
    }

    @Test
    void errorMessageIncludesPosition() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> TableDeserializer.fromJSON("{\"name\" \"t\"}"));
        assertTrue(e.getMessage().startsWith("Invalid JSON at position"), e.getMessage());
    }

    @Test
    void loadFromFileReadsSavedTable(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");
        Table original = usersTable();
        TableSerializer.saveToFile(original, file.toString());

        Table loaded = TableDeserializer.loadFromFile(file.toString());

        assertEquals(TableSerializer.toJSON(original), TableSerializer.toJSON(loaded));
    }

    @Test
    void loadFromFileThrowsForMissingFile(@TempDir Path dir) {
        assertThrows(IOException.class,
                () -> TableDeserializer.loadFromFile(dir.resolve("missing.json").toString()));
    }

    @Test
    void loadFromFileRejectsInvalidContent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "not json");
        assertThrows(IllegalArgumentException.class, () -> TableDeserializer.loadFromFile(file.toString()));
    }
}
