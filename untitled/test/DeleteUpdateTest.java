import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeleteUpdateTest {
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
        QueryEngine.insert(users, Map.of("id", 3, "name", "Carol", "active", true));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private void run(String command) {
        Parser.parse(command).execute(engine);
    }

    private List<Object> names() {
        return users.getRows().stream().map(r -> r.get("name")).toList();
    }

    // DELETE

    @Test
    void parsesDeleteWithAndWithoutWhere() {
        DeleteStatement all = assertInstanceOf(DeleteStatement.class, Parser.parse("DELETE users"));
        assertEquals("users", all.getTableName());
        assertTrue(all.getConditions().isEmpty());

        DeleteStatement some = assertInstanceOf(DeleteStatement.class, Parser.parse("delete users where id=2"));
        assertEquals("id", some.getConditions().get(0).getColumn());
        assertEquals(2, some.getConditions().get(0).getValue());
    }

    @Test
    void deleteWhereRemovesOnlyMatchingRows() {
        run("DELETE users WHERE active=true");

        assertEquals(List.of("Bob"), names());
        assertEquals("2 rows deleted", output.toString().trim());
    }

    @Test
    void deleteWithoutWhereRemovesEveryRow() {
        run("DELETE users");

        assertTrue(users.getRows().isEmpty());
        assertEquals("3 rows deleted", output.toString().trim());
    }

    @Test
    void deleteReportsSingularAndZeroCounts() {
        run("DELETE users WHERE id=2");
        run("DELETE users WHERE id=99");
        assertEquals("1 row deleted\n0 rows deleted", output.toString().trim().replace("\r\n", "\n"));
    }

    @Test
    void deleteRejectsUnknownTable() {
        assertThrows(IllegalArgumentException.class, () -> run("DELETE orders"));
    }

    @Test
    void deleteRejectsMalformedWhere() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("DELETE users WHERE"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("DELETE users id=1"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("DELETE users WHERE id"));
    }

    // UPDATE

    @Test
    void parsesUpdate() {
        UpdateStatement s = assertInstanceOf(UpdateStatement.class,
                Parser.parse("UPDATE users SET name=Robert active=true WHERE id=2"));
        assertEquals("users", s.getTableName());
        assertEquals(Map.of("name", "Robert", "active", true), s.getValues());
        assertEquals("id", s.getConditions().get(0).getColumn());
        assertEquals(2, s.getConditions().get(0).getValue());
    }

    @Test
    void updateWhereChangesOnlyMatchingRows() {
        run("UPDATE users SET name=Robert active=true WHERE id=2");

        assertEquals(List.of("Alice", "Robert", "Carol"), names());
        assertEquals(true, users.getRows().get(1).get("active"));
        assertEquals(2, users.getRows().get(1).get("id"), "columns not in SET are kept");
        assertEquals("1 row updated", output.toString().trim());
    }

    @Test
    void updateWithoutWhereChangesEveryRow() {
        run("UPDATE users SET active=false");

        assertTrue(users.getRows().stream().allMatch(r -> r.get("active").equals(false)));
        assertEquals("3 rows updated", output.toString().trim());
    }

    @Test
    void updateCanSetColumnThatWasEmpty() {
        QueryEngine.insert(users, Map.of("id", 4));
        run("UPDATE users SET name=Dave WHERE id=4");
        assertEquals("Dave", users.getRows().get(3).get("name"));
    }

    @Test
    void updateKeepsRowPositions() {
        run("UPDATE users SET name=X WHERE id=1");
        assertEquals(List.of(1, 2, 3), users.getRows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void updateRejectsWrongTypeAndChangesNothing() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run("UPDATE users SET id=abc"));
        assertEquals("Column 'id' expects INTEGER", e.getMessage());
        assertEquals(List.of(1, 2, 3), users.getRows().stream().map(r -> r.get("id")).toList());
    }

    @Test
    void updateRejectsUnknownColumn() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> run("UPDATE users SET age=30"));
        assertEquals("Unknown column 'age'", e.getMessage());
    }

    @Test
    void updateRejectsUnknownTable() {
        assertThrows(IllegalArgumentException.class, () -> run("UPDATE orders SET id=1"));
    }

    @Test
    void updateRejectsMalformedCommands() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("UPDATE users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("UPDATE users name=Bob"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("UPDATE users SET"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("UPDATE users SET WHERE id=1"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("UPDATE users SET name WHERE id=1"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("UPDATE users SET name=Bob WHERE"));
    }
}
