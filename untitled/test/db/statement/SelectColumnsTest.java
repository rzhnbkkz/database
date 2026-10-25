package db.statement;

import db.query.Expression;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SelectColumnsTest {
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
                new Column("name", DataType.VARCHAR),
                new Column("active", DataType.BOOLEAN)
        ));
        engine.addTable(users);
        QueryEngine.insert(users, Map.of("id", 1, "name", "Alice", "active", true));
        QueryEngine.insert(users, Map.of("id", 2, "name", "Bob", "active", false));
        QueryEngine.insert(users, Map.of("id", 3, "active", true));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private String run(String command) {
        Parser.parse(command).execute(engine);
        return output.toString().trim();
    }

    @Test
    void parsesColumnList() {
        SelectStatement s = assertInstanceOf(SelectStatement.class, Parser.parse("SELECT name, id FROM users"));
        assertEquals(List.of("name", "id"), s.getColumns());
        assertSame(Expression.ALL_ROWS, s.getWhere());
    }

    @Test
    void parsesColumnListWithWhere() {
        SelectStatement s = assertInstanceOf(SelectStatement.class, Parser.parse("SELECT name FROM users WHERE id > 1"));
        assertEquals(List.of("name"), s.getColumns());
        assertEquals("id>1", s.getWhere().toString());
    }

    @Test
    void starMeansEveryColumn() {
        assertTrue(((SelectStatement) Parser.parse("SELECT * FROM users")).getColumns().isEmpty());
        assertTrue(((SelectStatement) Parser.parse("SELECT * FROM users WHERE id = 1")).getColumns().isEmpty());
    }

    @Test
    void rejectsMissingColumnListOrFrom() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT FROM users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT *"));
    }

    @Test
    void printsOnlyRequestedColumnsInRequestedOrder() {
        assertEquals("[{name=Alice, id=1}, {name=Bob, id=2}, {id=3}]", run("SELECT name, id FROM users"));
    }

    @Test
    void combinesColumnsWithWhere() {
        assertEquals("[{name=Bob}]", run("SELECT name FROM users WHERE active = FALSE"));
    }

    @Test
    void starPrintsSameAsListingEveryColumn() {
        String all = run("SELECT * FROM users");
        output.reset();
        assertEquals(all, run("SELECT id, name, active FROM users"));
    }

    @Test
    void selectingColumnsDoesNotChangeTheTable() {
        run("SELECT name FROM users");
        assertEquals(Map.of("id", 1, "name", "Alice", "active", true), users.getRows().get(0).getData());
    }

    @Test
    void rejectsUnknownColumn() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run("SELECT name, age FROM users"));
        assertEquals("Unknown column 'age'", e.getMessage());
    }

    @Test
    void rejectsEmptyColumnNames() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT name, FROM users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT , name FROM users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT name,, id FROM users"));
    }

    @Test
    void rejectsColumnsWithoutCommas() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT name id FROM users"));
    }

    @Test
    void queryEngineSelectProjectsRows() {
        List<Row> rows = QueryEngine.select(users, List.of("id"), new Condition("active", true));
        assertEquals(List.of(Map.of("id", 1), Map.of("id", 3)), rows.stream().map(Row::getData).toList());
    }
}
