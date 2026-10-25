package db.query;

import db.model.Row;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExpressionTest {
    private static final Row ROW = new Row(Map.of("x", 1));
    // Evaluate to TRUE, FALSE and unknown (y is empty) on ROW
    private static final Expression T = new Condition("x", 1);
    private static final Expression F = new Condition("x", 2);
    private static final Expression U = new Condition("y", 1);

    @Test
    void conditionOnEmptyCellIsUnknown() {
        assertEquals(true, T.evaluate(ROW));
        assertEquals(false, F.evaluate(ROW));
        assertNull(U.evaluate(ROW));
        assertFalse(U.matches(ROW));
        assertNull(new Condition("x", null).evaluate(ROW));
    }

    @Test
    void andTruthTable() {
        assertEquals(true, new And(List.of(T, T)).evaluate(ROW));
        assertEquals(false, new And(List.of(T, F)).evaluate(ROW));
        assertEquals(false, new And(List.of(U, F)).evaluate(ROW));
        assertNull(new And(List.of(T, U)).evaluate(ROW));
        assertNull(new And(List.of(U, U)).evaluate(ROW));
    }

    @Test
    void orTruthTable() {
        assertEquals(true, new Or(List.of(F, T)).evaluate(ROW));
        assertEquals(true, new Or(List.of(U, T)).evaluate(ROW));
        assertEquals(false, new Or(List.of(F, F)).evaluate(ROW));
        assertNull(new Or(List.of(F, U)).evaluate(ROW));
    }

    @Test
    void notTruthTable() {
        assertEquals(false, new Not(T).evaluate(ROW));
        assertEquals(true, new Not(F).evaluate(ROW));
        assertNull(new Not(U).evaluate(ROW));
    }

    @Test
    void allRowsMatchesEveryRow() {
        assertTrue(Expression.ALL_ROWS.matches(ROW));
        assertTrue(Expression.ALL_ROWS.matches(new Row(Map.of())));
        assertEquals("TRUE", Expression.ALL_ROWS.toString());
    }

    @Test
    void toStringAddsParenthesesOnlyWhereNeeded() {
        Expression a = new Condition("a", 1), b = new Condition("b", 2), c = new Condition("c", 3);
        assertEquals("a=1 OR b=2 AND c=3", new Or(List.of(a, new And(List.of(b, c)))).toString());
        assertEquals("(a=1 OR b=2) AND c=3", new And(List.of(new Or(List.of(a, b)), c)).toString());
        assertEquals("NOT a=1", new Not(a).toString());
        assertEquals("NOT (a=1 OR b=2)", new Not(new Or(List.of(a, b))).toString());
    }
}
