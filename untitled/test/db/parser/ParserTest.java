package db.parser;

import db.query.Expression;
import db.query.Condition;
import db.query.ColumnRef;
import db.statement.InsertStatement;
import db.statement.SelectStatement;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    private List<String> columnNames(String select) {
        return ((SelectStatement) Parser.parse(select)).getColumns().stream().map(ColumnRef::toString).toList();
    }

    private List<Object> insertedValues(String values) {
        return ((InsertStatement) Parser.parse("INSERT INTO t VALUES (" + values + ")")).getValues();
    }

    @Test
    void parsesSelectWithoutCondition() {
        SelectStatement s = assertInstanceOf(SelectStatement.class, Parser.parse("SELECT * FROM users"));
        assertEquals("users", s.getTableName());
        assertTrue(s.getColumns().isEmpty());
        assertSame(Expression.ALL_ROWS, s.getWhere());
    }

    @Test
    void parsesSelectWithWhereCondition() {
        SelectStatement s = assertInstanceOf(SelectStatement.class,
                Parser.parse("SELECT * FROM users WHERE name = 'Alice'"));
        assertEquals("users", s.getTableName());
        assertEquals("name", ((Condition) s.getWhere()).getColumn());
        assertEquals("Alice", ((Condition) s.getWhere()).getValue());
    }

    @Test
    void keywordsAreCaseInsensitive() {
        SelectStatement s = assertInstanceOf(SelectStatement.class, Parser.parse("select * from users where id = 1"));
        assertEquals(1, ((Condition) s.getWhere()).getValue());
        assertInstanceOf(InsertStatement.class, Parser.parse("Insert Into users Values (1)"));
    }

    @Test
    void namesKeepTheirCase() {
        SelectStatement s = (SelectStatement) Parser.parse("SELECT userName FROM Users");
        assertEquals("Users", s.getTableName());
        assertEquals(List.of("userName"), columnNames("SELECT userName FROM Users"));
    }

    @Test
    void whitespaceIsOptionalAroundSymbols() {
        SelectStatement s = (SelectStatement) Parser.parse("   SELECT id,name FROM users WHERE id>=1   ");
        assertEquals(List.of("id", "name"), columnNames("SELECT id,name FROM users"));
        assertEquals("id>=1", s.getWhere().toString());
    }

    @Test
    void acceptsOneTrailingSemicolon() {
        assertInstanceOf(SelectStatement.class, Parser.parse("SELECT * FROM users;"));
        assertInstanceOf(SelectStatement.class, Parser.parse("SELECT * FROM users ;  "));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users;;"));
    }

    @Test
    void parsesInsertWithColumnList() {
        InsertStatement s = assertInstanceOf(InsertStatement.class,
                Parser.parse("INSERT INTO users (id, name, active) VALUES (4, 'Dave', TRUE)"));
        assertEquals("users", s.getTableName());
        assertEquals(List.of("id", "name", "active"), s.getColumns());
        assertEquals(List.of(4, "Dave", true), s.getValues());
    }

    @Test
    void parsesInsertWithoutColumnList() {
        InsertStatement s = assertInstanceOf(InsertStatement.class, Parser.parse("INSERT INTO users VALUES (1, 'A')"));
        assertTrue(s.getColumns().isEmpty());
        assertEquals(List.of(1, "A"), s.getValues());
    }

    @Test
    void rejectsMalformedInsert() {
        String[] bad = {
                "INSERT users VALUES (1)", "INSERT INTO users (1)", "INSERT INTO users VALUES ()",
                "INSERT INTO users VALUES 1", "INSERT INTO users VALUES (1", "INSERT INTO users VALUES (1,)",
                "INSERT INTO users () VALUES (1)", "INSERT INTO users (id VALUES (1)",
                "INSERT INTO users id=1",
        };
        for (String command : bad) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(command), command);
        }
    }

    @Test
    void rejectsColumnListedTwiceInInsert() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("INSERT INTO users (id, id) VALUES (1, 2)"));
        assertEquals("Column 'id' is listed twice", e.getMessage());
    }

    @Test
    void parsesValueTypes() {
        assertEquals(Arrays.asList(42, -7, 1.5f, -0.25f, .5f, true, false, null, "hello"),
                insertedValues("42, -7, 1.5, -0.25, .5, TRUE, false, NULL, 'hello'"));
    }

    @Test
    void numberTooBigForIntegerBecomesFloat() {
        assertInstanceOf(Float.class, insertedValues("3000000000").get(0));
    }

    @Test
    void numberWithDecimalPointIsAlwaysFloat() {
        assertEquals(List.of(2.0f), insertedValues("2.0"));
    }

    @Test
    void rejectsUnquotedTextAsValue() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> insertedValues("hello"));
        assertEquals("Expected a value, got 'hello' at position 23 (put text in quotes: 'hello')", e.getMessage());
    }

    @Test
    void keywordNeedsBackticksToBeAName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT from FROM users"));
        assertEquals("Expected column name, got keyword 'FROM' at position 8 (use `from` to use it as a name)",
                e.getMessage());

        SelectStatement s = (SelectStatement) Parser.parse("SELECT `from`, `two words` FROM `select`");
        assertEquals(List.of("from", "two words"), columnNames("SELECT `from`, `two words` FROM `select`"));
        assertEquals("select", s.getTableName());
    }

    @Test
    void saveAndLoadAreNotReservedAsNames() {
        assertEquals(List.of("save", "load"), columnNames("SELECT save, load FROM users"));
    }

    @Test
    void errorsNameWhatWasExpectedAndWhere() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT * users"));
        assertEquals("Expected FROM, got 'users' at position 10", e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM"));
        assertEquals("Expected table name, got end of input", e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users u extra"));
        assertEquals("Unexpected 'extra' at position 23", e.getMessage());
    }

    @Test
    void rejectsUnknownCharacters() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT * FROM users WHERE id = 1 # comment"));
        assertEquals("Unexpected character '#' at position 34", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users WHERE id ! 1"));
    }

    @Test
    void rejectsCommandWithoutArguments() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT"));
        assertEquals("Expected column name, got end of input", e.getMessage());
    }

    @Test
    void rejectsEmptyInput() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Parser.parse(""));
        assertEquals("Command is not recognized, try again", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(";"));
    }

    @Test
    void rejectsUnknownCommand() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Parser.parse("TRUNCATE users"));
        assertEquals("Command TRUNCATE is unrecognized", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("42"));
    }

    @Test
    void rejectsWhereWithoutCondition() {
        // The REPL only catches IllegalArgumentException, so anything else would crash it
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users WHERE"));
    }
}
