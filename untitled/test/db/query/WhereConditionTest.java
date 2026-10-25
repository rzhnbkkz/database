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
        return QueryEngine.selectWhere(users, s.getWhere()).stream().map(r -> r.get("id")).toList();
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
            Condition condition = (Condition) s.getWhere();
            assertEquals("id", condition.getColumn(), c[0]);
            assertEquals(c[1], condition.getOperator().getSymbol(), c[0]);
            assertEquals(1, condition.getValue(), c[0]);
        }
    }

    @Test
    void parsesNegativeNumberAfterOperator() {
        SelectStatement s = (SelectStatement) Parser.parse("SELECT * FROM users WHERE id > -5");
        assertEquals(Condition.Operator.GREATER, ((Condition) s.getWhere()).getOperator());
        assertEquals(-5, ((Condition) s.getWhere()).getValue());
    }

    @Test
    void parsesConditionsJoinedWithAnd() {
        SelectStatement s = (SelectStatement) Parser.parse("SELECT * FROM users WHERE id > 1 and active = true AND name != 'Bob'");
        assertEquals("id>1 AND active=true AND name!=Bob", s.getWhere().toString());
        assertEquals(3, ((And) s.getWhere()).parts().size(), "a chain of ANDs is one And node");
    }

    @Test
    void rejectsMalformedConditions() {
        String[] bad = {
                "WHERE id", "WHERE = 1", "WHERE id ! 1", "WHERE id = 1 AND", "WHERE id = 1 OR", "WHERE NOT", "WHERE (id = 1",
                "WHERE id = 1)", "WHERE ()", "WHERE NOT (id = 1", "WHERE id = 1 AND OR id = 2",
                "WHERE id = 1 id = 2", "WHERE id = = 1", "WHERE",
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
    void missingClosingParenthesisIsReported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT * FROM users WHERE (id = 1 OR id = 2"));
        assertEquals("Expected ')', got end of input", e.getMessage());
        e = assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users WHERE id = 1)"));
        assertEquals("Unexpected ')' at position 33", e.getMessage());
    }

    @Test
    void parsesOrNotAndParenthesesIntoATree() {
        String[][] cases = {
                // input, how the parser grouped it
                {"id = 1 OR id = 2 OR id = 3", "id=1 OR id=2 OR id=3"},
                {"id = 1 OR id = 2 AND active = TRUE", "id=1 OR id=2 AND active=true"},
                {"(id = 1 OR id = 2) AND active = TRUE", "(id=1 OR id=2) AND active=true"},
                {"NOT id = 1 AND active = TRUE", "NOT id=1 AND active=true"},
                {"NOT (id = 1 AND active = TRUE)", "NOT (id=1 AND active=true)"},
                {"NOT NOT id = 1", "NOT NOT id=1"},
                {"((id = 1))", "id=1"},
                {"id = 1 AND (id = 2 OR NOT (id = 3 AND id = 4))", "id=1 AND (id=2 OR NOT (id=3 AND id=4))"},
        };
        for (String[] c : cases) {
            assertEquals(c[1], ((SelectStatement) Parser.parse("SELECT * FROM users WHERE " + c[0])).getWhere().toString(), c[0]);
        }
    }

    @Test
    void chainOfOrsIsOneOrNode() {
        SelectStatement s = (SelectStatement) Parser.parse("SELECT * FROM users WHERE id = 1 OR id = 2 OR id = 3");
        assertEquals(3, ((Or) s.getWhere()).parts().size());
    }

    // OR, NOT and parentheses

    @Test
    void orNeedsAnyCondition() {
        assertEquals(List.of(1, 3), idsWhere("name = 'Alice' OR name = 'Carol'"));
        assertEquals(List.of(1, 2, 3), idsWhere("id = 1 OR id = 2 OR id = 3"));
        assertEquals(List.of(), idsWhere("id = 9 OR name = 'Zoe'"));
    }

    @Test
    void andBindsTighterThanOr() {
        // id = 1 OR (id = 2 AND active = TRUE): Bob is inactive, so only Alice
        assertEquals(List.of(1), idsWhere("id = 1 OR id = 2 AND active = TRUE"));
        assertEquals(List.of(1, 3), idsWhere("active = TRUE AND id = 1 OR id = 3"));
    }

    @Test
    void parenthesesChangeGrouping() {
        assertEquals(List.of(1), idsWhere("(id = 1 OR id = 2) AND active = TRUE"));
        assertEquals(List.of(1, 3), idsWhere("active = TRUE AND (id = 1 OR id = 3)"));
        assertEquals(List.of(3), idsWhere("active = TRUE AND (id = 2 OR id = 3)"));
    }

    @Test
    void notFlipsTheCondition() {
        assertEquals(List.of(2, 3, 4), idsWhere("NOT id = 1"));
        assertEquals(List.of(3, 4), idsWhere("NOT (id = 1 OR id = 2)"));
        assertEquals(List.of(1), idsWhere("NOT NOT id = 1"));
    }

    @Test
    void notBindsTighterThanAnd() {
        // (NOT id = 1) AND active = TRUE
        assertEquals(List.of(3), idsWhere("NOT id = 1 AND active = TRUE"));
        assertEquals(List.of(2, 3, 4), idsWhere("NOT (id = 1 AND active = TRUE)"));
    }

    @Test
    void notOfEmptyCellStillDoesNotMatch() {
        // Row 4 has no name or active value: the comparison is unknown, and NOT unknown is still unknown
        assertEquals(List.of(1, 3), idsWhere("NOT name = 'Bob'"));
        assertEquals(List.of(2), idsWhere("NOT active = TRUE"));
    }

    @Test
    void unknownCombinesLikeSql() {
        // Row 4: unknown OR TRUE is TRUE
        assertEquals(List.of(2, 4), idsWhere("name = 'Bob' OR id = 4"));
        // Row 4: unknown AND FALSE is FALSE, so NOT makes it TRUE
        assertEquals(List.of(1, 2, 3, 4), idsWhere("NOT (name = 'Bob' AND id = 1)"));
        // Row 4: unknown AND TRUE is unknown, and so is NOT of it
        assertEquals(List.of(1, 2, 3), idsWhere("NOT (name = 'Zoe' AND id = 4)"));
    }

    @Test
    void keywordsAreCaseInsensitive() {
        assertEquals(List.of(3, 4), idsWhere("not (id = 1 or id = 2)"));
    }

    @Test
    void parenthesesNeedNoSpaces() {
        assertEquals(List.of(1), idsWhere("(id=1 OR id=2)AND(active=TRUE)"));
    }

    @Test
    void unquotedTextErrorSuggestsQuotes() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT * FROM users WHERE name = Bob").execute(engine));
        assertEquals("Unknown column 'Bob' (to compare with text, put it in quotes: 'Bob')", e.getMessage());
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
        assertEquals(4, QueryEngine.selectWhere(users, Expression.ALL_ROWS).size());
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

    @Test
    void deleteWithOrAndNot() {
        Parser.parse("DELETE FROM users WHERE id = 1 OR NOT (active = TRUE)").execute(engine);
        assertEquals(List.of(3, 4), users.getRows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void updateWithOrAndParentheses() {
        Parser.parse("UPDATE users SET score = 0 WHERE (id = 1 OR id = 2) AND active = TRUE").execute(engine);
        assertEquals(List.of(0.0f, 7.0f, 4.25f), users.getRows().stream().limit(3).map(r -> r.get("score")).toList());
    }
}
