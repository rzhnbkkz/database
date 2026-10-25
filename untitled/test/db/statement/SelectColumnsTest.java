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
        SelectStatement s = assertInstanceOf(SelectStatement.class, Parser.parse("SELECT users name,id"));
        assertEquals(List.of("name", "id"), s.getColumns());
        assertTrue(s.getConditions().isEmpty());
    }

    @Test
    void parsesColumnListWithWhere() {
        SelectStatement s = assertInstanceOf(SelectStatement.class, Parser.parse("SELECT users name WHERE id>1"));
        assertEquals(List.of("name"), s.getColumns());
        assertEquals("id>1", s.getConditions().get(0).toString());
    }

    @Test
    void starAndMissingListMeanEveryColumn() {
        assertTrue(((SelectStatement) Parser.parse("SELECT users *")).getColumns().isEmpty());
        assertTrue(((SelectStatement) Parser.parse("SELECT users * WHERE id=1")).getColumns().isEmpty());
        assertTrue(((SelectStatement) Parser.parse("SELECT users")).getColumns().isEmpty());
    }

    @Test
    void printsOnlyRequestedColumnsInRequestedOrder() {
        assertEquals("[{name=Alice, id=1}, {name=Bob, id=2}, {id=3}]", run("SELECT users name,id"));
    }

    @Test
    void combinesColumnsWithWhere() {
        assertEquals("[{name=Bob}]", run("SELECT users name WHERE active=false"));
    }

    @Test
    void starPrintsSameAsNoColumnList() {
        String all = run("SELECT users");
        output.reset();
        assertEquals(all, run("SELECT users *"));
    }

    @Test
    void selectingColumnsDoesNotChangeTheTable() {
        run("SELECT users name");
        assertEquals(Map.of("id", 1, "name", "Alice", "active", true), users.getRows().get(0).getData());
    }

    @Test
    void rejectsUnknownColumn() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run("SELECT users name,age"));
        assertEquals("Unknown column 'age'", e.getMessage());
    }

    @Test
    void rejectsEmptyColumnNames() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT users name,"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT users ,name"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT users name,,id"));
    }

    @Test
    void rejectsTokensAfterColumnListOtherThanWhere() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT users name id"));
    }

    @Test
    void queryEngineSelectProjectsRows() {
        List<Row> rows = QueryEngine.select(users, List.of("id"), List.of(new Condition("active", true)));
        assertEquals(List.of(Map.of("id", 1), Map.of("id", 3)), rows.stream().map(Row::getData).toList());
    }
}
