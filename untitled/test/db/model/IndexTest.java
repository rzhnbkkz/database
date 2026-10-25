package db.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexTest {
    private Table users;

    @BeforeEach
    void setUp() {
        users = new Table("users", List.of(
                new Column("id", DataType.INTEGER, true),
                new Column("name", DataType.VARCHAR),
                new Column("score", DataType.FLOAT)
        ));
        users.insert(new Row(Map.of("id", 1, "name", "Alice", "score", 2f)));
        users.insert(new Row(Map.of("id", 2, "name", "Bob")));
        users.insert(new Row(Map.of("id", 3, "name", "Alice", "score", 7.5f)));
    }

    private List<Object> idsWhere(String column, Object value) {
        return users.getRows(users.getIndexOn(column).lookup(value)).stream().map(r -> r.get("id")).toList();
    }

    @Test
    void primaryKeyGetsAnIndex() {
        assertEquals(List.of("PRIMARY"), users.getIndexes().stream().map(Index::getName).toList());
        assertEquals("id", users.getIndexOn("id").getColumn());
        assertEquals(List.of(2), idsWhere("id", 2));
    }

    @Test
    void tableWithoutPrimaryKeyHasNoIndexes() {
        assertTrue(new Table("t", List.of(new Column("a", DataType.INTEGER))).getIndexes().isEmpty());
    }

    @Test
    void createIndexCoversExistingRows() {
        users.createIndex("idx_name", "name");
        assertEquals(List.of(1, 3), idsWhere("name", "Alice"));
        assertEquals(List.of(), idsWhere("name", "Zoe"));
        assertNull(users.getIndexOn("score"));
    }

    @Test
    void lookupReturnsRowsInTableOrder() {
        users.createIndex("idx_name", "name");
        users.insert(new Row(Map.of("id", 0, "name", "Alice")));
        assertEquals(List.of(1, 3, 0), idsWhere("name", "Alice"));
    }

    @Test
    void numbersMatchByValueLikeWhere() {
        users.createIndex("idx_score", "score");
        assertEquals(List.of(1), idsWhere("score", 2));
        assertEquals(List.of(1), idsWhere("score", 2.0f));
        assertEquals(List.of(2), idsWhere("id", 2.0f));
    }

    @Test
    void mismatchedTypesAndNullFindNothing() {
        assertEquals(List.of(), idsWhere("id", "2"));
        assertEquals(List.of(), idsWhere("id", true));
        assertEquals(List.of(), idsWhere("id", null));
    }

    @Test
    void emptyCellsAreNotIndexed() {
        users.createIndex("idx_score", "score");
        assertEquals(List.of(), idsWhere("score", null));
    }

    @Test
    void insertsAreIndexed() {
        users.createIndex("idx_name", "name");
        users.insert(new Row(Map.of("id", 4, "name", "Bob")));
        assertEquals(List.of(2, 4), idsWhere("name", "Bob"));
    }

    @Test
    void deletesLeaveTheIndex() {
        users.createIndex("idx_name", "name");
        users.delete(row -> row.get("id").equals(1));
        assertEquals(List.of(3), idsWhere("name", "Alice"));
        assertEquals(List.of(), idsWhere("id", 1));
    }

    @Test
    void updatesMoveRowsBetweenKeys() {
        users.createIndex("idx_name", "name");
        users.update(row -> row.get("id").equals(1), Map.of("name", "Bob", "id", 10));
        assertEquals(List.of(3), idsWhere("name", "Alice"));
        assertEquals(List.of(10, 2), idsWhere("name", "Bob"), "the row keeps its place in the table");
        assertEquals(List.of(10), idsWhere("id", 10));
        assertEquals(List.of(), idsWhere("id", 1));
    }

    @Test
    void updateToEmptyCellRemovesItFromTheIndex() {
        users.createIndex("idx_name", "name");
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("name", null);
        users.update(row -> row.get("id").equals(1), values);
        assertEquals(List.of(3), idsWhere("name", "Alice"));
    }

    @Test
    void deleteAndUpdateCanBeLimitedToCandidates() {
        // Only row ids 0 and 2 (Alice and the second Alice) are tested
        assertEquals(1, users.delete(List.of(0L, 1L), row -> row.get("name").equals("Alice")));
        assertEquals(List.of(2, 3), users.getRows().stream().map(r -> r.get("id")).toList());
        assertEquals(1, users.update(List.of(2L), row -> true, Map.of("name", "Carol")));
        assertEquals("Carol", users.getRows().get(1).get("name"));
    }

    @Test
    void primaryKeyStillRejectsDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> users.insert(new Row(Map.of("id", 2))));
        assertThrows(IllegalArgumentException.class,
                () -> users.update(row -> row.get("id").equals(1), Map.of("id", 3)));
        users.update(row -> row.get("id").equals(1), Map.of("id", 1));
        assertEquals(List.of(1), idsWhere("id", 1), "setting a key to its own value is fine");
    }

    @Test
    void createIndexRejectsBadRequests() {
        users.createIndex("idx_name", "name");
        assertEquals("Index 'idx_name' already exists on table 'users'",
                assertThrows(IllegalArgumentException.class, () -> users.createIndex("idx_name", "score")).getMessage());
        assertEquals("Column 'name' already has index 'idx_name'",
                assertThrows(IllegalArgumentException.class, () -> users.createIndex("other", "name")).getMessage());
        assertEquals("Column 'id' already has index 'PRIMARY'",
                assertThrows(IllegalArgumentException.class, () -> users.createIndex("other", "id")).getMessage());
        assertEquals("Unknown column 'age'",
                assertThrows(IllegalArgumentException.class, () -> users.createIndex("other", "age")).getMessage());
        assertEquals("The name PRIMARY is reserved for the primary key index",
                assertThrows(IllegalArgumentException.class, () -> users.createIndex("primary", "score")).getMessage());
    }

    @Test
    void dropIndex() {
        users.createIndex("idx_name", "name");
        users.dropIndex("idx_name");
        assertNull(users.getIndexOn("name"));
        assertEquals("Index 'idx_name' does not exist on table 'users'",
                assertThrows(IllegalArgumentException.class, () -> users.dropIndex("idx_name")).getMessage());
        assertEquals("Cannot drop the PRIMARY index, it belongs to the primary key",
                assertThrows(IllegalArgumentException.class, () -> users.dropIndex("PRIMARY")).getMessage());
    }
}
