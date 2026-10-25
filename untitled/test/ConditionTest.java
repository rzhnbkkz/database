import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionTest {

    @Test
    void gettersReturnConstructorValues() {
        Condition condition = new Condition("id", 1);
        assertEquals("id", condition.getColumn());
        assertEquals(1, condition.getValue());
    }

    @Test
    void matchesRowWithEqualValue() {
        assertTrue(new Condition("name", "Alice").matches(new Row(Map.of("name", "Alice"))));
    }

    @Test
    void doesNotMatchRowWithDifferentValue() {
        assertFalse(new Condition("name", "Alice").matches(new Row(Map.of("name", "Bob"))));
    }

    @Test
    void doesNotMatchRowMissingTheColumn() {
        assertFalse(new Condition("name", "Alice").matches(new Row(Map.of("id", 1))));
    }

    @Test
    void comparisonIsTypeSensitive() {
        assertFalse(new Condition("id", "1").matches(new Row(Map.of("id", 1))));
    }
}
