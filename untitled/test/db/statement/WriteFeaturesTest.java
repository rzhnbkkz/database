package db.statement;

import db.model.Column;
import db.model.Index;
import db.model.Row;
import db.model.Table;
import db.parser.Parser;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Multi-row INSERT, NOT NULL, DEFAULT, AUTO_INCREMENT, DROP TABLE and ALTER TABLE
class WriteFeaturesTest {
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private QueryEngine engine;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(output));
        engine = new QueryEngine();
        run("CREATE TABLE users (id INTEGER PRIMARY KEY AUTO_INCREMENT, name VARCHAR NOT NULL, city VARCHAR DEFAULT 'Oslo', "
                + "active BOOLEAN NOT NULL DEFAULT TRUE, score FLOAT DEFAULT 0)");
        output.reset();
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private void run(String sql) {
        Parser.parse(sql).execute(engine);
    }

    private String printed(String sql) {
        output.reset();
        run(sql);
        return output.toString().trim();
    }

    private String fails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> run(sql)).getMessage();
    }

    private List<Map<String, Object>> select(String sql) {
        return engine.select(((SelectStatement) Parser.parse(sql)).getQuery()).stream().map(Row::getData).toList();
    }

    private Table users() {
        return engine.getTable("users");
    }

    // Multi-row INSERT

    @Test
    void parsesSeveralRows() {
        InsertStatement s = (InsertStatement) Parser.parse("INSERT INTO users (id, name) VALUES (1, 'A'), (2, 'B'), (3, DEFAULT)");
        assertEquals(3, s.getRows().size());
        assertEquals(List.of(1, "A"), s.getValues());
        assertSame(Table.DEFAULT, s.getRows().get(2).get(1));
        for (String bad : new String[]{"VALUES (1, 'A'),", "VALUES (1, 'A') (2, 'B')", "VALUES (1, 'A'), ()", "VALUES (1, 'A'), 2"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("INSERT INTO users (id, name) " + bad), bad);
        }
    }

    @Test
    void insertsEveryRowAndReportsNewIds() {
        assertEquals("3 rows inserted (id = 1, 2, 3)", printed("INSERT INTO users (name) VALUES ('Alice'), ('Bob'), ('Carol')"));
        assertEquals(List.of("Alice", "Bob", "Carol"), users().getRows().stream().map(r -> r.get("name")).toList());
        assertEquals("1 row inserted", printed("INSERT INTO users (id, name) VALUES (50, 'Dan')"));
    }

    @Test
    void oneBadRowInsertsNothing() {
        run("INSERT INTO users (name) VALUES ('Alice')");
        assertEquals("Column 'score' expects FLOAT", fails("INSERT INTO users (name, score) VALUES ('Bob', 1), ('Carol', 'high')"));
        assertEquals("Duplicate primary key: id=7", fails("INSERT INTO users (id, name) VALUES (7, 'X'), (7, 'Y')"));
        assertEquals("Duplicate primary key: id=1", fails("INSERT INTO users (id, name) VALUES (9, 'X'), (1, 'Y')"));
        assertEquals("Expected 1 value, got 2 in row 2", fails("INSERT INTO users (name) VALUES ('X'), ('Y', 'Z')"));
        assertEquals(1, users().getRows().size());
        // The failed inserts didn't use up numbers either
        assertEquals("1 row inserted (id = 2)", printed("INSERT INTO users (name) VALUES ('Bob')"));
    }

    @Test
    void uniqueIsCheckedAcrossTheNewRows() {
        run("CREATE UNIQUE INDEX idx_name ON users (name)");
        assertEquals("Duplicate value for unique index 'idx_name': name=A", fails("INSERT INTO users (name) VALUES ('A'), ('B'), ('A')"));
        assertTrue(users().getRows().isEmpty());
    }

    // DEFAULT

    @Test
    void leftOutColumnsGetTheirDefault() {
        run("INSERT INTO users (name) VALUES ('Alice')");
        assertEquals(Map.of("id", 1, "name", "Alice", "city", "Oslo", "active", true, "score", 0f), users().getRows().get(0).getData());
    }

    @Test
    void explicitNullStaysEmpty() {
        run("INSERT INTO users (name, city, score) VALUES ('Alice', NULL, NULL)");
        assertEquals(Map.of("id", 1, "name", "Alice", "active", true), users().getRows().get(0).getData());
        assertEquals("[{id=1, name=Alice, active=true}]", printed("SELECT * FROM users"));
    }

    @Test
    void defaultKeywordAsAValue() {
        run("INSERT INTO users VALUES (DEFAULT, 'Alice', DEFAULT, FALSE, DEFAULT)");
        assertEquals(Map.of("id", 1, "name", "Alice", "city", "Oslo", "active", false, "score", 0f), users().getRows().get(0).getData());
        run("UPDATE users SET city = 'Rome', score = 5 WHERE id = 1");
        run("UPDATE users SET city = DEFAULT, score = DEFAULT, active = DEFAULT WHERE id = 1");
        assertEquals(Map.of("id", 1, "name", "Alice", "city", "Oslo", "active", true, "score", 0f), users().getRows().get(0).getData());
    }

    @Test
    void defaultWithoutADefaultIsEmpty() {
        run("CREATE TABLE t (a INTEGER, b INTEGER)");
        run("INSERT INTO t VALUES (1, DEFAULT)");
        run("UPDATE t SET a = DEFAULT");
        assertEquals(Map.of(), engine.getTable("t").getRows().get(0).getData());
    }

    @Test
    void defaultsAreTypeChecked() {
        assertEquals("DEFAULT for column 'n' must be INTEGER", fails("CREATE TABLE t (n INTEGER DEFAULT 'x')"));
        assertEquals("DEFAULT for column 'b' must be BOOLEAN", fails("CREATE TABLE t (b BOOLEAN DEFAULT 1)"));
        run("CREATE TABLE t (f FLOAT DEFAULT 2, s VARCHAR DEFAULT 'it''s', n INTEGER DEFAULT -3)");
        run("INSERT INTO t (n) VALUES (1)");
        assertEquals(Map.of("f", 2f, "s", "it's", "n", 1), engine.getTable("t").getRows().get(0).getData());
        assertEquals("Column 'n' has two DEFAULTs", assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("CREATE TABLE t2 (n INTEGER DEFAULT 1 DEFAULT 2)")).getMessage());
    }

    // NOT NULL

    @Test
    void notNullColumnsMustHaveAValue() {
        assertEquals("Column 'name' cannot be empty", fails("INSERT INTO users (city) VALUES ('Rome')"));
        assertEquals("Column 'name' cannot be empty", fails("INSERT INTO users (name) VALUES (NULL)"));
        assertEquals("Column 'active' cannot be empty", fails("INSERT INTO users (name, active) VALUES ('A', NULL)"));
        assertTrue(users().getRows().isEmpty());
    }

    @Test
    void updatesCannotEmptyANotNullColumn() {
        run("INSERT INTO users (name) VALUES ('Alice')");
        assertEquals("Column 'name' cannot be empty", fails("UPDATE users SET name = NULL"));
        // Columns that aren't NOT NULL can still be emptied, and other updates are fine
        run("UPDATE users SET city = NULL, score = 3");
        assertEquals(Map.of("id", 1, "name", "Alice", "active", true, "score", 3f), users().getRows().get(0).getData());
    }

    @Test
    void columnOptionsInAnyOrder() {
        CreateStatement s = (CreateStatement) Parser.parse(
                "CREATE TABLE t (id INT AUTO_INCREMENT PRIMARY KEY, a VARCHAR(20) DEFAULT 'x' NOT NULL, b INT NULL)");
        Column id = s.getColumns().get(0), a = s.getColumns().get(1), b = s.getColumns().get(2);
        assertTrue(id.isPrimaryKey() && id.isAutoIncrement());
        assertTrue(a.isNotNull());
        assertEquals("x", a.getDefaultValue());
        assertFalse(b.isNotNull());
        assertEquals("Column 'n' can't be both NULL and NOT NULL",
                assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE t (n INT NULL NOT NULL)")).getMessage());
    }

    // AUTO_INCREMENT

    @Test
    void autoIncrementCountsUp() {
        run("INSERT INTO users (name) VALUES ('A')");
        run("INSERT INTO users (id, name) VALUES (NULL, 'B')");
        run("INSERT INTO users VALUES (DEFAULT, 'C', DEFAULT, DEFAULT, DEFAULT)");
        assertEquals(List.of(1, 2, 3), users().getRows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void numbersGivenByHandMoveTheCounter() {
        run("INSERT INTO users (id, name) VALUES (10, 'A')");
        assertEquals("1 row inserted (id = 11)", printed("INSERT INTO users (name) VALUES ('B')"));
        // A smaller hand-picked number doesn't move it back
        run("INSERT INTO users (id, name) VALUES (5, 'C')");
        assertEquals("3 rows inserted (id = 12, 21)", printed("INSERT INTO users (id, name) VALUES (NULL, 'D'), (20, 'E'), (NULL, 'F')"));
    }

    @Test
    void numbersOfDeletedRowsArentReused() {
        run("INSERT INTO users (name) VALUES ('A'), ('B'), ('C')");
        run("DELETE FROM users WHERE id >= 2");
        assertEquals("1 row inserted (id = 4)", printed("INSERT INTO users (name) VALUES ('D')"));
    }

    @Test
    void updatingTheKeyMovesTheCounter() {
        run("INSERT INTO users (name) VALUES ('A')");
        run("UPDATE users SET id = 30 WHERE id = 1");
        assertEquals("1 row inserted (id = 31)", printed("INSERT INTO users (name) VALUES ('B')"));
    }

    @Test
    void autoIncrementRules() {
        assertEquals("AUTO_INCREMENT column 'n' must be INTEGER", fails("CREATE TABLE t (n VARCHAR PRIMARY KEY AUTO_INCREMENT)"));
        assertEquals("AUTO_INCREMENT column 'n' must be the PRIMARY KEY", fails("CREATE TABLE t (n INTEGER AUTO_INCREMENT)"));
        assertEquals("AUTO_INCREMENT column 'n' can't have a DEFAULT", fails("CREATE TABLE t (n INTEGER PRIMARY KEY AUTO_INCREMENT DEFAULT 1)"));
    }

    @Test
    void autoIncrementStopsAtTheLargestInteger() {
        run("INSERT INTO users (id, name) VALUES (2147483647, 'Last')");
        assertEquals("AUTO_INCREMENT column 'id' has used up every INTEGER", fails("INSERT INTO users (name) VALUES ('Next')"));
    }

    // CREATE TABLE IF NOT EXISTS and DROP TABLE

    @Test
    void createIfNotExists() {
        assertEquals("Table 'users' already exists, nothing created", printed("CREATE TABLE IF NOT EXISTS users (x INTEGER)"));
        assertEquals(5, users().getColumns().size());
        assertEquals("Table 't' created", printed("CREATE TABLE IF NOT EXISTS t (x INTEGER)"));
        assertTrue(((CreateStatement) Parser.parse("CREATE TABLE IF NOT EXISTS t (x INTEGER)")).isIfNotExists());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE IF EXISTS t (x INTEGER)"));
    }

    @Test
    void dropTable() {
        assertEquals("Table 'users' dropped", printed("DROP TABLE users"));
        assertNull(engine.getTable("users"));
        assertEquals("Table 'users' does not exist", fails("DROP TABLE users"));
        assertEquals("Table 'users' does not exist, nothing dropped", printed("DROP TABLE IF EXISTS users"));
        // The name is free again
        run("CREATE TABLE users (x INTEGER)");
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("DROP TABLE IF users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("DROP users"));
    }

    // ALTER TABLE

    @Test
    void addColumnFillsExistingRowsWithItsDefault() {
        run("INSERT INTO users (name) VALUES ('A'), ('B')");
        assertEquals("Column 'level' added to users", printed("ALTER TABLE users ADD COLUMN level INTEGER NOT NULL DEFAULT 1"));
        run("ALTER TABLE users ADD note VARCHAR");
        assertEquals(List.of(Map.of("id", 1, "level", 1), Map.of("id", 2, "level", 1)), select("SELECT id, level, note FROM users"));
        run("INSERT INTO users (name, note) VALUES ('C', 'hi')");
        assertEquals(Map.of("level", 1, "note", "hi"), select("SELECT level, note FROM users WHERE name = 'C'").get(0));
    }

    @Test
    void addColumnRules() {
        assertEquals("Column 'name' already exists", fails("ALTER TABLE users ADD name VARCHAR"));
        assertEquals("A PRIMARY KEY or AUTO_INCREMENT column can't be added to an existing table",
                fails("ALTER TABLE users ADD code INTEGER PRIMARY KEY"));
        // NOT NULL without a DEFAULT only works while the table is empty
        run("ALTER TABLE users ADD email VARCHAR NOT NULL");
        run("INSERT INTO users (name, email) VALUES ('A', 'a@x')");
        assertEquals("Column 'phone' is NOT NULL, so it needs a DEFAULT for the existing rows",
                fails("ALTER TABLE users ADD phone VARCHAR NOT NULL"));
        assertEquals("DEFAULT for column 'n' must be INTEGER", fails("ALTER TABLE users ADD n INTEGER DEFAULT 'x'"));
    }

    @Test
    void dropColumnRemovesItsDataAndIndexes() {
        run("INSERT INTO users (name, city) VALUES ('A', 'Rome')");
        run("CREATE INDEX idx_city ON users (city)");
        run("CREATE INDEX idx_city_name ON users (city, name)");
        run("CREATE INDEX idx_name ON users (name)");
        assertEquals("Column 'city' dropped from users, along with indexes idx_city, idx_city_name",
                printed("ALTER TABLE users DROP COLUMN city"));
        assertEquals(Map.of("id", 1, "name", "A", "active", true, "score", 0f), users().getRows().get(0).getData());
        assertEquals(List.of("PRIMARY", "idx_name"), users().getIndexes().stream().map(Index::getName).toList());
        assertEquals("Unknown column 'city'", fails("SELECT city FROM users"));
        assertEquals("Column 'score' dropped from users", printed("ALTER TABLE users DROP score"));
    }

    @Test
    void dropColumnRules() {
        assertEquals("Can't drop the primary key column 'id'", fails("ALTER TABLE users DROP COLUMN id"));
        assertEquals("Unknown column 'nope'", fails("ALTER TABLE users DROP COLUMN nope"));
        run("CREATE TABLE t (only INTEGER)");
        assertEquals("Can't drop the only column of table 't'", fails("ALTER TABLE t DROP only"));
    }

    @Test
    void renameColumnKeepsDataKeysAndIndexes() {
        run("INSERT INTO users (name, city) VALUES ('A', 'Rome'), ('B', 'Oslo')");
        run("CREATE INDEX idx_city_name ON users (city, name)");
        assertEquals("Column 'city' renamed to 'town'", printed("ALTER TABLE users RENAME COLUMN city TO town"));
        assertEquals(List.of("id", "name", "town", "active", "score"), users().getColumns().stream().map(Column::getName).toList());
        assertEquals(List.of(Map.of("name", "B")), select("SELECT name FROM users WHERE town = 'Oslo'"));
        assertEquals(List.of("users: index idx_city_name (town=Oslo)"),
                engine.explain(((SelectStatement) Parser.parse("SELECT * FROM users WHERE town = 'Oslo'")).getQuery()));
        // The DEFAULT moves with it
        run("INSERT INTO users (name) VALUES ('C')");
        assertEquals("Oslo", users().getRows().get(2).get("town"));
    }

    @Test
    void renamingThePrimaryKeyKeepsItWorking() {
        run("INSERT INTO users (name) VALUES ('A')");
        run("ALTER TABLE users RENAME COLUMN id TO user_id");
        assertEquals("user_id", users().getPrimaryKey().getName());
        assertEquals("Duplicate primary key: user_id=1", fails("INSERT INTO users (user_id, name) VALUES (1, 'B')"));
        assertEquals("1 row inserted (user_id = 2)", printed("INSERT INTO users (name) VALUES ('B')"));
    }

    @Test
    void renameColumnRules() {
        assertEquals("Column 'name' already exists", fails("ALTER TABLE users RENAME COLUMN city TO name"));
        assertEquals("Unknown column 'nope'", fails("ALTER TABLE users RENAME COLUMN nope TO x"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("ALTER TABLE users RENAME COLUMN city town"));
    }

    @Test
    void renameTable() {
        run("INSERT INTO users (name) VALUES ('A')");
        assertEquals("Table 'users' renamed to 'people'", printed("ALTER TABLE users RENAME TO people"));
        assertNull(engine.getTable("users"));
        assertEquals("people", engine.getTable("people").getName());
        assertEquals(List.of(Map.of("name", "A")), select("SELECT name FROM people"));
        run("ALTER TABLE people RENAME persons");
        run("CREATE TABLE t (x INTEGER)");
        assertEquals("Table 't' already exists", fails("ALTER TABLE persons RENAME TO t"));
        assertEquals("Table 'nope' does not exist", fails("ALTER TABLE nope RENAME TO x"));
    }

    @Test
    void rejectsMalformedAlter() {
        for (String bad : new String[]{"ALTER users ADD x INTEGER", "ALTER TABLE users", "ALTER TABLE users ADD", "ALTER TABLE users ADD x",
                "ALTER TABLE users MODIFY x INTEGER", "ALTER TABLE users DROP", "ALTER TABLE users RENAME"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(bad), bad);
        }
    }

    // SAVE and LOAD

    @Test
    void columnOptionsAndTheCounterAreSavedAndLoaded() throws Exception {
        run("INSERT INTO users (name) VALUES ('A'), ('B'), ('C')");
        run("DELETE FROM users WHERE id = 3");
        Path file = dir.resolve("users.json");
        run("SAVE users '" + file + "'");
        String json = Files.readString(file);
        assertTrue(json.contains("{\"name\": \"id\", \"type\": \"INTEGER\", \"primaryKey\": true, \"autoIncrement\": true}"), json);
        assertTrue(json.contains("{\"name\": \"active\", \"type\": \"BOOLEAN\", \"notNull\": true, \"default\": true}"), json);
        assertTrue(json.contains("\"nextAutoIncrement\": 4"), json);

        engine = new QueryEngine();
        run("LOAD '" + file + "'");
        // 3 was deleted before saving, so the next number is still 4
        assertEquals("1 row inserted (id = 4)", printed("INSERT INTO users (name) VALUES ('D')"));
        assertEquals(Map.of("id", 4, "name", "D", "city", "Oslo", "active", true, "score", 0f), users().getRows().get(2).getData());
        assertEquals("Column 'name' cannot be empty", fails("INSERT INTO users (city) VALUES ('Rome')"));
    }

    @Test
    void loadRejectsRowsThatBreakNotNull() throws Exception {
        Path file = dir.resolve("bad.json");
        Files.writeString(file, "{\"name\": \"t\", \"columns\": [{\"name\": \"a\", \"type\": \"INTEGER\", \"notNull\": true}], "
                + "\"rows\": [{\"a\": null}]}");
        assertEquals("Column 'a' cannot be empty", fails("LOAD '" + file + "'"));
        Files.writeString(file, "{\"name\": \"t\", \"columns\": [{\"name\": \"a\", \"type\": \"INTEGER\", \"notNull\": 1}]}");
        assertEquals("Expected notNull to be true or false", fails("LOAD '" + file + "'"));
    }
}
