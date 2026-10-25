package db.query;

import db.model.Column;
import db.model.DataType;
import db.model.Table;
import db.parser.Parser;
import db.statement.SelectStatement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WhereConditionTest {
    private final PrintStream originalOut = System.out;
    private QueryEngine engine;
    private Table users;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        engine = new QueryEngine();
        users = new Table("users", List.of(
                new Column("id", DataType.INTEGER),
                new Column("name", DataType.VARCHAR),
                new Column("active", DataType.BOOLEAN),
                new Column("score", DataType.FLOAT)
        ));
        engine.addTable(users);
        QueryEngine.insert(users, Map.of("id", 1, "name", "Alice", "active", true, "score", 9.5f));
        QueryEngine.insert(users, Map.of("id", 2, "name", "Bob", "active", false, "score", 7.0f));
        QueryEngine.insert(users, Map.of("id", 3, "name", "Carol", "active", true, "score", 4.25f));
        QueryEngine.insert(users, Map.of("id", 4));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private List<Object> idsWhere(String where) {
        SelectStatement s = (SelectStatement) Parser.parse("SELECT * FROM users WHERE " + where);
        return QueryEngine.selectWhere(users, s.getConditions()).stream().map(r -> r.get("id")).toList();
    }

    // Parsing

    @Test
    void parsesEveryOperator() {
        String[][] cases = {
                {"id = 1", "="}, {"id != 1", "!="}, {"id <> 1", "!="}, {"id>1", ">"}, {"id < 1", "<"}, {"id >= 1", ">="},
                {"id<=1", "<="},
        };
        for (String[] c : cases) {
            SelectStatement s = (SelectStatement) Parser.parse("SELECT * FROM users WHERE " + c[0]);
            Condition condition = s.getConditions().get(0);
            assertEquals("id", condition.getColumn(), c[0]);
            assertEquals(c[1], condition.getOperator().getSymbol(), c[0]);
            assertEquals(1, condition.getValue(), c[0]);
        }
    }

    @Test
    void parsesNegativeNumberAfterOperator() {
        SelectStatement s = (SelectStatement) Parser.parse("SELECT * FROM users WHERE id > -5");
        assertEquals(Condition.Operator.GREATER, s.getConditions().get(0).getOperator());
        assertEquals(-5, s.getConditions().get(0).getValue());
    }

    @Test
    void parsesConditionsJoinedWithAnd() {
        SelectStatement s = (SelectStatement) Parser.parse("SELECT * FROM users WHERE id > 1 and active = true AND name != 'Bob'");
        assertEquals(List.of("id>1", "active=true", "name!=Bob"),
                s.getConditions().stream().map(Condition::toString).toList());
    }

    @Test
    void rejectsMalformedConditions() {
        String[] bad = {
                "WHERE id", "WHERE = 1", "WHERE id ! 1", "WHERE id = 1 AND", "WHERE id = 1 OR id = 2",
                "WHERE id = 1 id = 2", "WHERE id = Bob", "WHERE id = = 1", "WHERE",
        };
        for (String where : bad) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users " + where), where);
        }
    }

    @Test
    void andErrorNamesTheKeyword() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT * FROM users WHERE id = 1 AND"));
        assertEquals("Expected column name, got end of input", e.getMessage());
    }

    @Test
    void orErrorSaysItIsNotSupported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT * FROM users WHERE id = 1 OR id = 2"));
        assertEquals("OR is not supported yet, only AND", e.getMessage());
    }

    @Test
    void unquotedTextErrorSuggestsQuotes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT * FROM users WHERE name = Bob"));
        assertEquals("Expected a value, got 'Bob' at position 34 (put text in quotes: 'Bob')", e.getMessage());
    }

    // Matching

    @Test
    void equalsAndNotEquals() {
        assertEquals(List.of(2), idsWhere("name = 'Bob'"));
        assertEquals(List.of(1, 3), idsWhere("name != 'Bob'"));
    }

    @Test
    void numericComparisons() {
        assertEquals(List.of(3, 4), idsWhere("id>2"));
        assertEquals(List.of(2, 3, 4), idsWhere("id>=2"));
        assertEquals(List.of(1), idsWhere("id<2"));
        assertEquals(List.of(1, 2), idsWhere("id<=2"));
    }

    @Test
    void floatComparisons() {
        assertEquals(List.of(1, 2), idsWhere("score>5"));
        assertEquals(List.of(3), idsWhere("score<4.5"));
    }

    @Test
    void integerAndFloatValuesCompareByNumber() {
        assertEquals(List.of(2), idsWhere("score=7"));
        assertEquals(List.of(2), idsWhere("id=2.0"));
    }

    @Test
    void stringComparisonsAreAlphabetical() {
        assertEquals(List.of(2, 3), idsWhere("name > 'Alice'"));
        assertEquals(List.of(1, 2), idsWhere("name < 'Carol'"));
    }

    @Test
    void booleanEquality() {
        assertEquals(List.of(1, 3), idsWhere("active=true"));
        assertEquals(List.of(2), idsWhere("active!=true"));
    }

    @Test
    void emptyCellsNeverMatch() {
        // Row 4 has only an id, so it's left out of every name/score condition, even !=
        assertEquals(List.of(1, 3), idsWhere("name != 'Bob'"));
        assertFalse(idsWhere("score<100").contains(4));
    }

    @Test
    void mismatchedTypesNeverMatchExceptNotEquals() {
        assertEquals(List.of(), idsWhere("name = 5"));
        assertEquals(List.of(), idsWhere("name > 5"));
        assertEquals(List.of(1, 2, 3), idsWhere("name != 5"));
    }

    @Test
    void andRequiresEveryCondition() {
        assertEquals(List.of(3), idsWhere("active = TRUE AND id > 1"));
        assertEquals(List.of(2, 3), idsWhere("id>=2 AND id<=3"));
        assertEquals(List.of(), idsWhere("active=true AND active=false"));
    }

    @Test
    void emptyConditionListMatchesEveryRow() {
        assertEquals(4, QueryEngine.selectWhere(users, List.of()).size());
    }

    // Operators and AND in DELETE and UPDATE

    @Test
    void deleteWithOperatorsAndAnd() {
        Parser.parse("DELETE FROM users WHERE id > 1 AND active = true").execute(engine);
        assertEquals(List.of(1, 2, 4), users.getRows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void updateWithOperators() {
        Parser.parse("UPDATE users SET active = false WHERE score >= 7").execute(engine);
        assertEquals(List.of(1, 2), idsWhere("active=false"));
        assertEquals(List.of(3), idsWhere("active=true"));
    }
}
