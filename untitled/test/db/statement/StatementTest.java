package db.statement;

import db.model.Column;
import db.model.DataType;
import db.model.Row;
import db.model.Table;
import db.parser.Parser;
import db.query.Condition;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StatementTest {
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private QueryEngine engine;
    private Table users;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(output));
        engine = new QueryEngine();
        users = new Table("users", List.of(
                new Column("id", DataType.INTEGER),
                new Column("name", DataType.VARCHAR)
        ));
        engine.addTable(users);
        QueryEngine.insert(users, Map.of("id", 1, "name", "Alice"));
        QueryEngine.insert(users, Map.of("id", 2, "name", "Bob"));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    // Rows don't guarantee column order, so compare each printed row's "key=value" cells sorted
    private static List<List<String>> sortedCells(String printed) {
        String inner = printed.substring(1, printed.length() - 1);
        if (inner.isEmpty()) return List.of();
        return Arrays.stream(inner.split("}, \\{"))
                .map(row -> Arrays.stream(row.replaceAll("[{}]", "").split(", ")).sorted().toList())
                .toList();
    }

    private void assertPrinted(String expected) {
        assertEquals(sortedCells(expected), sortedCells(output.toString().trim()));
    }

    @Test
    void insertStatementAddsRowToNamedTable() {
        new InsertStatement("users", new Row(Map.of("id", 3, "name", "Carol"))).execute(engine);

        assertEquals(3, users.getRows().size());
        assertEquals("Carol", users.getRows().get(2).get("name"));
    }

    @Test
    void insertStatementValidatesTypes() {
        Statement s = new InsertStatement("users", new Row(Map.of("id", "three")));
        assertThrows(IllegalArgumentException.class, () -> s.execute(engine));
    }

    @Test
    void insertWithoutColumnListUsesTableOrder() {
        new InsertStatement("users", List.of(), List.of(3, "Carol")).execute(engine);
        assertEquals(Map.of("id", 3, "name", "Carol"), users.getRows().get(2).getData());
    }

    @Test
    void insertWithColumnListMayReorderAndSkipColumns() {
        new InsertStatement("users", List.of("name", "id"), List.of("Carol", 3)).execute(engine);
        new InsertStatement("users", List.of("id"), List.of(4)).execute(engine);
        assertEquals(Map.of("id", 3, "name", "Carol"), users.getRows().get(2).getData());
        assertNull(users.getRows().get(3).get("name"));
    }

    @Test
    void insertRejectsWrongNumberOfValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new InsertStatement("users", List.of(), List.of(3)).execute(engine));
        assertEquals("Expected 2 values, got 1", e.getMessage());
        e = assertThrows(IllegalArgumentException.class,
                () -> new InsertStatement("users", List.of("id"), List.of(3, "Carol")).execute(engine));
        assertEquals("Expected 1 value, got 2", e.getMessage());
        assertEquals(2, users.getRows().size());
    }

    @Test
    void insertRejectsUnknownColumn() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("INSERT INTO users (id, age) VALUES (3, 30)").execute(engine));
        assertEquals("Unknown column 'age'", e.getMessage());
    }

    @Test
    void insertNullLeavesCellEmpty() {
        Parser.parse("INSERT INTO users VALUES (3, NULL)").execute(engine);
        assertNull(users.getRows().get(2).get("name"));
    }

    @Test
    void selectStatementPrintsAllRows() {
        new SelectStatement("users").execute(engine);
        assertPrinted("[{id=1, name=Alice}, {id=2, name=Bob}]");
    }

    @Test
    void selectStatementPrintsOnlyMatchingRows() {
        new SelectStatement("users", new Condition("name", "Bob")).execute(engine);
        assertPrinted("[{id=2, name=Bob}]");
    }

    @Test
    void selectStatementPrintsEmptyListWhenNothingMatches() {
        new SelectStatement("users", new Condition("name", "Zoe")).execute(engine);
        assertPrinted("[]");
    }

    @Test
    void parsedStatementsRunEndToEnd() {
        Parser.parse("INSERT INTO users (id, name) VALUES (3, 'Carol')").execute(engine);
        assertEquals("1 row inserted", output.toString().trim());
        output.reset();
        Parser.parse("SELECT * FROM users WHERE id = 3").execute(engine);
        assertPrinted("[{id=3, name=Carol}]");
    }

    @Test
    void selectFromUnknownTableIsRejected() {
        // The REPL only catches IllegalArgumentException, so anything else would crash it
        assertThrows(IllegalArgumentException.class, () -> new SelectStatement("orders").execute(engine));
    }

    @Test
    void insertIntoUnknownTableIsRejected() {
        Statement s = new InsertStatement("orders", new Row(Map.of("id", 1)));
        assertThrows(IllegalArgumentException.class, () -> s.execute(engine));
    }
}
