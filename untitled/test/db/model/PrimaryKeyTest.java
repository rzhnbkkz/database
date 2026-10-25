package db.model;

import db.parser.Parser;
import db.query.QueryEngine;
import db.statement.CreateStatement;
import db.storage.TableDeserializer;
import db.storage.TableSerializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrimaryKeyTest {
    private final PrintStream originalOut = System.out;
    private QueryEngine engine;
    private Table users;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        engine = new QueryEngine();
        run("CREATE users id:INTEGER:PRIMARY name:VARCHAR");
        users = engine.getTable("users");
        run("INSERT users id=1 name=Alice");
        run("INSERT users id=2 name=Bob");
        run("INSERT users id=3 name=Carol");
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private void run(String command) {
        Parser.parse(command).execute(engine);
    }

    private IllegalArgumentException fails(String command) {
        return assertThrows(IllegalArgumentException.class, () -> run(command));
    }

    private List<Object> ids() {
        return users.getRows().stream().map(r -> r.get("id")).toList();
    }

    // Declaring the key

    @Test
    void createMarksPrimaryKeyColumn() {
        CreateStatement s = (CreateStatement) Parser.parse("CREATE t code:VARCHAR:primary n:INTEGER");
        assertTrue(s.getColumns().get(0).isPrimaryKey());
        assertFalse(s.getColumns().get(1).isPrimaryKey());
        assertEquals("id", users.getPrimaryKey().getName());
    }

    @Test
    void tableWithoutPrimaryKeyHasNone() {
        run("CREATE logs msg:VARCHAR");
        assertNull(engine.getTable("logs").getPrimaryKey());
        run("INSERT logs msg=a");
        run("INSERT logs msg=a");
        assertEquals(2, engine.getTable("logs").getRows().size(), "duplicates are fine without a key");
    }

    @Test
    void rejectsMoreThanOnePrimaryKey() {
        assertEquals("A table can have only one primary key", fails("CREATE t a:INTEGER:PRIMARY b:INTEGER:PRIMARY").getMessage());
        assertNull(engine.getTable("t"));
    }

    @Test
    void rejectsUnknownColumnModifier() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("CREATE t id:INTEGER:UNIQUE"));
        assertEquals("Expected PRIMARY after the type, got: UNIQUE", e.getMessage());
    }

    @Test
    void primaryKeyCanBeText() {
        run("CREATE codes code:VARCHAR:PRIMARY");
        run("INSERT codes code=\"A 1\"");
        assertEquals("Duplicate primary key: code=A 1", fails("INSERT codes code=\"A 1\"").getMessage());
    }

    // INSERT

    @Test
    void insertRejectsDuplicateKey() {
        assertEquals("Duplicate primary key: id=2", fails("INSERT users id=2 name=Bobby").getMessage());
        assertEquals(List.of(1, 2, 3), ids());
    }

    @Test
    void insertRejectsMissingKey() {
        assertEquals("Primary key 'id' cannot be empty", fails("INSERT users name=Nobody").getMessage());
        assertEquals(3, users.getRows().size());
    }

    @Test
    void insertRejectsNullKey() {
        Map<String, Object> values = new HashMap<>();
        values.put("id", null);
        values.put("name", "Nobody");
        assertThrows(IllegalArgumentException.class, () -> users.insert(new Row(values)));
    }

    @Test
    void wrongTypeIsReportedBeforeKeyCheck() {
        assertEquals("Column 'id' expects INTEGER", fails("INSERT users id=abc").getMessage());
    }

    @Test
    void floatKeyTreatsWholeNumberAndFloatAsSameKey() {
        run("CREATE prices p:FLOAT:PRIMARY");
        run("INSERT prices p=7");
        assertThrows(IllegalArgumentException.class, () -> run("INSERT prices p=7.0"));
    }

    // DELETE

    @Test
    void deletedKeyCanBeReused() {
        run("DELETE users WHERE id=2");
        run("INSERT users id=2 name=Bobby");
        assertEquals("Bobby", users.getRows().get(2).get("name"));
    }

    @Test
    void deleteAllFreesEveryKey() {
        run("DELETE users");
        run("INSERT users id=1 name=Again");
        assertEquals(List.of(1), ids());
    }

    @Test
    void deleteKeepsOtherKeysTaken() {
        run("DELETE users WHERE id=2");
        assertThrows(IllegalArgumentException.class, () -> run("INSERT users id=1"));
    }

    // UPDATE

    @Test
    void updateOfOtherColumnsLeavesKeyAlone() {
        run("UPDATE users SET name=X");
        assertEquals(List.of(1, 2, 3), ids());
    }

    @Test
    void updateCanChangeKeyToUnusedValue() {
        run("UPDATE users SET id=10 WHERE id=2");
        assertEquals(List.of(1, 10, 3), ids());

        // The old key is free and the new one is taken
        run("INSERT users id=2 name=New");
        assertThrows(IllegalArgumentException.class, () -> run("INSERT users id=10"));
    }

    @Test
    void updateCanSetKeyToItsOwnValue() {
        run("UPDATE users SET id=2 name=Bobby WHERE id=2");
        assertEquals("Bobby", users.getRows().get(1).get("name"));
    }

    @Test
    void updateRejectsKeyThatBelongsToAnotherRow() {
        assertEquals("Duplicate primary key: id=1", fails("UPDATE users SET id=1 name=X WHERE id=2").getMessage());
        assertEquals(List.of(1, 2, 3), ids());
        assertEquals("Bob", users.getRows().get(1).get("name"), "nothing changes when the update fails");
    }

    @Test
    void updateRejectsSameKeyOnSeveralRows() {
        IllegalArgumentException e = fails("UPDATE users SET id=10 WHERE id>=2");
        assertEquals("Cannot set primary key 'id' to the same value on 2 rows", e.getMessage());
        assertEquals(List.of(1, 2, 3), ids());
    }

    @Test
    void updateOfKeyMatchingNoRowsIsAllowed() {
        run("UPDATE users SET id=1 WHERE id=99");
        assertEquals(List.of(1, 2, 3), ids());
    }

    @Test
    void updateRejectsEmptyKey() {
        Map<String, Object> values = new HashMap<>();
        values.put("id", null);
        assertThrows(IllegalArgumentException.class, () -> users.update(row -> row.get("id").equals(1), values));
        assertEquals(List.of(1, 2, 3), ids());
    }

    // SAVE / LOAD

    @Test
    void serializerMarksOnlyThePrimaryKeyColumn() {
        String json = TableSerializer.toJSON(users);
        assertTrue(json.contains("{\"name\": \"id\", \"type\": \"INTEGER\", \"primaryKey\": true}"), json);
        assertTrue(json.contains("{\"name\": \"name\", \"type\": \"VARCHAR\"}"), json);
    }

    @Test
    void primaryKeySurvivesSaveAndLoad() {
        Path file = dir.resolve("users.json");
        run("SAVE users " + file);

        engine = new QueryEngine();
        run("LOAD " + file);
        users = engine.getTable("users");

        assertEquals("id", users.getPrimaryKey().getName());
        assertEquals(List.of(1, 2, 3), ids());
        assertThrows(IllegalArgumentException.class, () -> run("INSERT users id=3"));
    }

    @Test
    void loadRejectsFileWithDuplicateKeys() throws IOException {
        Path file = dir.resolve("dupes.json");
        Files.writeString(file, """
                {"name": "dupes",
                 "columns": [{"name": "id", "type": "INTEGER", "primaryKey": true}],
                 "rows": [{"id": 1}, {"id": 1}]}
                """);
        assertEquals("Duplicate primary key: id=1", fails("LOAD " + file).getMessage());
        assertNull(engine.getTable("dupes"));
    }

    @Test
    void loadRejectsFileWithMissingKey() throws IOException {
        Path file = dir.resolve("missing.json");
        Files.writeString(file, """
                {"name": "missing",
                 "columns": [{"name": "id", "type": "INTEGER", "primaryKey": true}, {"name": "n", "type": "INTEGER"}],
                 "rows": [{"n": 1}]}
                """);
        assertThrows(IllegalArgumentException.class, () -> run("LOAD " + file));
    }

    @Test
    void loadRejectsNonBooleanPrimaryKeyFlag() {
        String json = "{\"name\": \"t\", \"columns\": [{\"name\": \"id\", \"type\": \"INTEGER\", \"primaryKey\": \"yes\"}]}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> TableDeserializer.fromJSON(json));
        assertEquals("Expected primaryKey to be true or false", e.getMessage());
    }

    @Test
    void filesWithoutPrimaryKeyFlagStillLoad() {
        String json = "{\"name\": \"t\", \"columns\": [{\"name\": \"id\", \"type\": \"INTEGER\"}], \"rows\": [{\"id\": 1}, {\"id\": 1}]}";
        Table t = TableDeserializer.fromJSON(json);
        assertNull(t.getPrimaryKey());
        assertEquals(2, t.getRows().size());
    }
}
