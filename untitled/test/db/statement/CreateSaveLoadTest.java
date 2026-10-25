package db.statement;

import db.model.Column;
import db.model.DataType;
import db.model.Row;
import db.model.Table;
import db.parser.Parser;
import db.query.QueryEngine;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CreateSaveLoadTest {
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private QueryEngine engine;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(output));
        engine = new QueryEngine();
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private void run(String command) {
        Parser.parse(command).execute(engine);
    }

    // CREATE

    @Test
    void parsesCreateWithColumnsAndTypes() {
        CreateStatement s = assertInstanceOf(CreateStatement.class,
                Parser.parse("CREATE TABLE users (id INTEGER, name varchar, active Boolean, score FLOAT)"));
        assertEquals("users", s.getTableName());
        assertEquals(List.of("id", "name", "active", "score"), s.getColumns().stream().map(Column::getName).toList());
        assertEquals(List.of(DataType.INTEGER, DataType.VARCHAR, DataType.BOOLEAN, DataType.FLOAT),
                s.getColumns().stream().map(Column::getDataType).toList());
    }

    @Test
    void parsesTypeAliasesAndVarcharLength() {
        CreateStatement s = assertInstanceOf(CreateStatement.class,
                Parser.parse("create table t (a INT, b VARCHAR(50), c BOOL);"));
        assertEquals(List.of(DataType.INTEGER, DataType.VARCHAR, DataType.BOOLEAN),
                s.getColumns().stream().map(Column::getDataType).toList());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE t (a VARCHAR())"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE t (a VARCHAR(1.5))"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE t (a INTEGER(5))"));
    }

    @Test
    void createAddsEmptyTableToEngine() {
        run("CREATE TABLE users (id INTEGER, name VARCHAR)");

        Table t = engine.getTable("users");
        assertNotNull(t);
        assertEquals(2, t.getColumns().size());
        assertTrue(t.getRows().isEmpty());
        assertEquals("Table 'users' created", output.toString().trim());
    }

    @Test
    void createdTableAcceptsInsertsAndValidatesTypes() {
        run("CREATE TABLE users (id INTEGER, name VARCHAR)");
        run("INSERT INTO users VALUES (1, 'Alice')");

        assertEquals(1, engine.getTable("users").getRows().size());
        assertThrows(IllegalArgumentException.class, () -> run("INSERT INTO users (id) VALUES ('Bob')"));
    }

    @Test
    void createRejectsExistingTable() {
        run("CREATE TABLE users (id INTEGER)");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run("CREATE TABLE users (id INTEGER)"));
        assertEquals("Table 'users' already exists", e.getMessage());
    }

    @Test
    void createRejectsMissingColumns() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE users ()"));
        assertEquals("Expected column name, got ')' at position 21", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE users (id INTEGER)"));
    }

    @Test
    void createRejectsBadColumnDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE users (id)"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE users (INTEGER)"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE users (id INTEGER name VARCHAR)"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE users (id INTEGER"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("CREATE TABLE users (id NUMBER)"));
        assertEquals("Unknown type NUMBER, expected one of [INTEGER, VARCHAR, BOOLEAN, FLOAT]", e.getMessage());
    }

    @Test
    void createRejectsDuplicateColumns() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("CREATE TABLE users (id INTEGER, id VARCHAR)"));
        assertEquals("Duplicate column: id", e.getMessage());
    }

    // SAVE

    @Test
    void parsesSave() {
        SaveStatement s = assertInstanceOf(SaveStatement.class, Parser.parse("save users '/tmp/users.json'"));
        assertEquals("users", s.getTableName());
        assertEquals("/tmp/users.json", s.getPath());
    }

    @Test
    void saveWritesTableAsJson() throws IOException {
        run("CREATE TABLE users (id INTEGER, name VARCHAR)");
        run("INSERT INTO users VALUES (1, 'Alice')");
        Path file = dir.resolve("users.json");

        run("SAVE users '" + file + "'");

        assertEquals(TableSerializer.toJSON(engine.getTable("users")), Files.readString(file));
        assertTrue(output.toString().contains("Table 'users' saved to " + file));
    }

    @Test
    void saveRejectsUnknownTable() {
        assertThrows(IllegalArgumentException.class, () -> run("SAVE orders '" + dir.resolve("o.json") + "'"));
    }

    @Test
    void saveReportsUnwritablePath() {
        run("CREATE TABLE users (id INTEGER)");
        Path file = dir.resolve("no-such-folder").resolve("users.json");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run("SAVE users '" + file + "'"));
        assertTrue(e.getMessage().startsWith("Could not save to " + file), e.getMessage());
    }

    @Test
    void saveRejectsWrongArgumentCount() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SAVE users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SAVE users 'a.json' extra"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SAVE users a.json"));
    }

    // LOAD

    @Test
    void parsesLoad() {
        LoadStatement s = assertInstanceOf(LoadStatement.class, Parser.parse("LOAD \"/tmp/users.json\""));
        assertEquals("/tmp/users.json", s.getPath());
    }

    @Test
    void loadAddsTableFromFile() throws IOException {
        Table users = new Table("users", List.of(new Column("id", DataType.INTEGER)));
        users.insert(new Row(Map.of("id", 1)));
        users.insert(new Row(Map.of("id", 2)));
        Path file = dir.resolve("users.json");
        TableSerializer.saveToFile(users, file.toString());

        run("LOAD '" + file + "'");

        assertEquals(TableSerializer.toJSON(users), TableSerializer.toJSON(engine.getTable("users")));
        assertEquals("Table 'users' loaded with 2 rows", output.toString().trim());
    }

    @Test
    void loadRejectsTableThatAlreadyExists() throws IOException {
        Path file = dir.resolve("users.json");
        TableSerializer.saveToFile(new Table("users", List.of()), file.toString());
        run("CREATE TABLE users (id INTEGER)");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run("LOAD '" + file + "'"));
        assertEquals("Table 'users' already exists", e.getMessage());
        assertEquals(1, engine.getTable("users").getColumns().size(), "existing table must be kept");
    }

    @Test
    void loadReportsMissingFile() {
        Path file = dir.resolve("missing.json");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run("LOAD '" + file + "'"));
        assertTrue(e.getMessage().startsWith("Could not read " + file), e.getMessage());
    }

    @Test
    void loadReportsInvalidFile() throws IOException {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "not json");
        assertThrows(IllegalArgumentException.class, () -> run("LOAD '" + file + "'"));
        assertNull(engine.getTable("bad"));
    }

    @Test
    void loadRejectsWrongArgumentCount() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("LOAD 'a.json' 'b.json'"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("LOAD a.json"));
    }

    // Full flow

    @Test
    void createInsertSaveThenLoadIntoFreshEngine() {
        Path file = dir.resolve("users.json");
        run("CREATE TABLE users (id INTEGER, name VARCHAR, active BOOLEAN)");
        run("INSERT INTO users VALUES (1, 'Alice', TRUE)");
        run("INSERT INTO users VALUES (2, 'Bob', FALSE)");
        run("SAVE users '" + file + "'");

        engine = new QueryEngine();
        run("LOAD '" + file + "'");

        List<Row> rows = engine.getTable("users").getRows();
        assertEquals(2, rows.size());
        assertEquals("Bob", rows.get(1).get("name"));
        assertEquals(false, rows.get(1).get("active"));
    }
}
