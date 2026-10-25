package db.storage;

import db.model.Column;
import db.model.DataType;
import db.model.Row;
import db.model.Table;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TableSerializerTest {

    private static Table usersTable() {
        Table t = new Table("users", List.of(
                new Column("id", DataType.INTEGER),
                new Column("name", DataType.VARCHAR),
                new Column("active", DataType.BOOLEAN),
                new Column("score", DataType.FLOAT)
        ));
        t.insert(new Row(Map.of("id", 1, "name", "Alice", "active", true, "score", 1.5f)));
        t.insert(new Row(Map.of("id", 2, "name", "Bob")));
        return t;
    }

    @Test
    void writesNameColumnsAndRows() {
        String expected = """
                {
                  "name": "users",
                  "columns": [
                    {"name": "id", "type": "INTEGER"},
                    {"name": "name", "type": "VARCHAR"},
                    {"name": "active", "type": "BOOLEAN"},
                    {"name": "score", "type": "FLOAT"}
                  ],
                  "rows": [
                    {"id": 1, "name": "Alice", "active": true, "score": 1.5},
                    {"id": 2, "name": "Bob", "active": null, "score": null}
                  ]
                }
                """;
        assertEquals(expected, TableSerializer.toJSON(usersTable()));
    }

    @Test
    void writesEmptyTable() {
        String expected = """
                {
                  "name": "empty",
                  "columns": [],
                  "rows": []
                }
                """;
        assertEquals(expected, TableSerializer.toJSON(new Table("empty", List.of())));
    }

    @Test
    void writesTableWithColumnsButNoRows() {
        Table t = new Table("t", List.of(new Column("id", DataType.INTEGER)));
        assertTrue(TableSerializer.toJSON(t).contains("\"rows\": []"));
    }

    @Test
    void escapesSpecialCharactersInStrings() {
        Table t = new Table("t", List.of(new Column("s", DataType.VARCHAR)));
        t.insert(new Row(Map.of("s", "a\"b\\c\nd\te\u0001")));

        assertTrue(TableSerializer.toJSON(t).contains("{\"s\": \"a\\\"b\\\\c\\nd\\te\\u0001\"}"));
    }

    @Test
    void writesNaNAndInfinityAsNull() {
        Table t = new Table("t", List.of(new Column("a", DataType.FLOAT), new Column("b", DataType.FLOAT)));
        t.insert(new Row(Map.of("a", Float.NaN, "b", Float.POSITIVE_INFINITY)));

        assertTrue(TableSerializer.toJSON(t).contains("{\"a\": null, \"b\": null}"));
    }

    @Test
    void saveToFileWritesJson(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");
        Table t = usersTable();

        TableSerializer.saveToFile(t, file.toString());

        assertEquals(TableSerializer.toJSON(t), Files.readString(file));
    }

    @Test
    void saveToFileOverwritesExistingFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");
        Files.writeString(file, "old content that is longer than nothing");
        Table t = new Table("empty", List.of());

        TableSerializer.saveToFile(t, file.toString());

        assertEquals(TableSerializer.toJSON(t), Files.readString(file));
    }
}
