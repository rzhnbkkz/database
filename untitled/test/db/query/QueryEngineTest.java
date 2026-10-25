package db.query;

import db.model.Column;
import db.model.DataType;
import db.model.Row;
import db.model.Table;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineTest {
    private QueryEngine engine;
    private Table users;

    @BeforeEach
    void setUp() {
        engine = new QueryEngine();
        users = new Table("users", List.of(
                new Column("id", DataType.INTEGER),
                new Column("name", DataType.VARCHAR),
                new Column("active", DataType.BOOLEAN)
        ));
        engine.addTable(users);
        QueryEngine.insert(users, Map.of("id", 1, "name", "Alice", "active", true));
        QueryEngine.insert(users, Map.of("id", 2, "name", "Bob", "active", false));
        QueryEngine.insert(users, Map.of("id", 3, "name", "Carol", "active", true));
    }

    @Test
    void getTableReturnsAddedTable() {
        assertSame(users, engine.getTable("users"));
    }

    @Test
    void getTableReturnsNullForUnknownName() {
        assertNull(engine.getTable("orders"));
    }

    @Test
    void addTableWithSameNameReplacesExistingTable() {
        Table replacement = new Table("users", List.of());
        engine.addTable(replacement);
        assertSame(replacement, engine.getTable("users"));
    }

    @Test
    void insertAddsRowToTable() {
        QueryEngine.insert(users, Map.of("id", 4, "name", "Dave"));
        assertEquals(4, users.getRows().size());
        assertEquals("Dave", users.getRows().get(3).get("name"));
    }

    @Test
    void insertValidatesTypes() {
        assertThrows(IllegalArgumentException.class, () -> QueryEngine.insert(users, Map.of("id", "four")));
    }

    @Test
    void selectAllReturnsEveryRow() {
        assertEquals(3, QueryEngine.selectAll(users).size());
    }

    @Test
    void selectAllReturnsIndependentCopy() {
        List<Row> result = QueryEngine.selectAll(users);
        result.clear();
        assertEquals(3, users.getRows().size());
    }

    @Test
    void selectWhereReturnsMatchingRows() {
        List<Row> result = QueryEngine.selectWhere(users, "active", true);
        List<Object> names = result.stream().map(r -> r.get("name")).toList();
        assertEquals(List.of("Alice", "Carol"), names);
    }

    @Test
    void selectWhereReturnsEmptyListWhenNothingMatches() {
        assertTrue(QueryEngine.selectWhere(users, "name", "Zoe").isEmpty());
    }

    @Test
    void selectWhereOnUnknownColumnReturnsEmptyList() {
        assertTrue(QueryEngine.selectWhere(users, "age", 30).isEmpty());
    }

    @Test
    void selectWhereSkipsRowsWithNullInColumn() {
        QueryEngine.insert(users, Map.of("id", 4));
        assertTrue(QueryEngine.selectWhere(users, "name", null).isEmpty());
    }
}
