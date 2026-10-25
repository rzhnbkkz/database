package db.parser;

import db.statement.InsertStatement;
import db.statement.SelectStatement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @Test
    void parsesSelectWithoutCondition() {
        SelectStatement s = assertInstanceOf(SelectStatement.class, Parser.parse("SELECT users"));
        assertEquals("users", s.getTableName());
        assertTrue(s.getConditions().isEmpty());
    }

    @Test
    void parsesSelectWithWhereCondition() {
        SelectStatement s = assertInstanceOf(SelectStatement.class, Parser.parse("SELECT users WHERE name=Alice"));
        assertEquals("users", s.getTableName());
        assertEquals("name", s.getConditions().get(0).getColumn());
        assertEquals("Alice", s.getConditions().get(0).getValue());
    }

    @Test
    void keywordsAreCaseInsensitive() {
        SelectStatement s = assertInstanceOf(SelectStatement.class, Parser.parse("select users where id=1"));
        assertEquals(1, s.getConditions().get(0).getValue());
        assertInstanceOf(InsertStatement.class, Parser.parse("insert users id=1"));
    }

    @Test
    void ignoresSurroundingAndRepeatedWhitespace() {
        SelectStatement s = assertInstanceOf(SelectStatement.class, Parser.parse("   SELECT    users   "));
        assertEquals("users", s.getTableName());
    }

    @Test
    void parsesInsertWithAssignments() {
        InsertStatement s = assertInstanceOf(InsertStatement.class,
                Parser.parse("INSERT users id=4 name=Dave active=true"));
        assertEquals("users", s.getTableName());
        assertEquals(4, s.getRow().get("id"));
        assertEquals("Dave", s.getRow().get("name"));
        assertEquals(true, s.getRow().get("active"));
    }

    @Test
    void parsesInsertWithoutValues() {
        InsertStatement s = assertInstanceOf(InsertStatement.class, Parser.parse("INSERT users"));
        assertTrue(s.getRow().getData().isEmpty());
    }

    @Test
    void valueAfterFirstEqualsSignIsKeptWhole() {
        InsertStatement s = assertInstanceOf(InsertStatement.class, Parser.parse("INSERT users expr=a=b"));
        assertEquals("a=b", s.getRow().get("expr"));
    }

    @Test
    void parsesValueTypes() {
        InsertStatement s = assertInstanceOf(InsertStatement.class,
                Parser.parse("INSERT t i=42 neg=-7 f=1.5 b1=TRUE b2=false s=hello"));
        assertEquals(42, s.getRow().get("i"));
        assertEquals(-7, s.getRow().get("neg"));
        assertEquals(1.5f, s.getRow().get("f"));
        assertEquals(true, s.getRow().get("b1"));
        assertEquals(false, s.getRow().get("b2"));
        assertEquals("hello", s.getRow().get("s"));
    }

    @Test
    void numberTooBigForIntegerBecomesFloat() {
        InsertStatement s = assertInstanceOf(InsertStatement.class, Parser.parse("INSERT t n=3000000000"));
        assertInstanceOf(Float.class, s.getRow().get("n"));
    }

    @Test
    void rejectsSingleWordCommand() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT"));
        assertEquals("Command is not recognized, try again", e.getMessage());
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("   "));
    }

    @Test
    void rejectsUnknownCommand() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Parser.parse("DROP users"));
        assertEquals("Command DROP is unrecognized", e.getMessage());
    }

    @Test
    void rejectsAssignmentWithoutEqualsSign() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("INSERT users name"));
        assertEquals("Expected key=value, got: name", e.getMessage());
    }

    @Test
    void rejectsUnexpectedTokensInSelect() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT users WHERE id=1 name=Bob"));
        assertEquals("Unexpected name=Bob after condition, expected AND", e.getMessage());
    }

    @Test
    void rejectsWhereWithoutCondition() {
        // The REPL only catches IllegalArgumentException, so anything else would crash it
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT users WHERE"));
    }
}
