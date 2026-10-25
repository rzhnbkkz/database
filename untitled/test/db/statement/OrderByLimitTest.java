package db.statement;

import db.parser.Parser;
import db.query.OrderBy;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrderByLimitTest {
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(output));
        engine = new QueryEngine();
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR, age INTEGER, score FLOAT, active BOOLEAN)");
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total FLOAT)");
        run("INSERT INTO users VALUES (1, 'Carol', 30, 7.5, TRUE)");
        run("INSERT INTO users VALUES (2, 'Alice', 25, 9, FALSE)");
        run("INSERT INTO users VALUES (3, 'Bob', 30, 7.5, TRUE)");
        run("INSERT INTO users (id, name) VALUES (4, 'Dan')");
        run("INSERT INTO orders VALUES (10, 1, 9.5)");
        run("INSERT INTO orders VALUES (11, 1, 20)");
        run("INSERT INTO orders VALUES (12, 2, 5)");
        output.reset();
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private void run(String sql) {
        Parser.parse(sql).execute(engine);
    }

    private SelectStatement parse(String sql) {
        return (SelectStatement) Parser.parse(sql);
    }

    private List<Map<String, Object>> select(String sql) {
        SelectStatement s = parse(sql);
        return engine.select(s.getFrom(), s.getJoins(), s.getColumns(), s.getWhere(),
                s.getOrderBy(), s.getLimit(), s.getOffset()).stream().map(r -> r.getData()).toList();
    }

    private List<Object> ids(String sql) {
        return select(sql).stream().map(r -> r.get("id")).toList();
    }

    // Parsing

    @Test
    void parsesOrderByItems() {
        SelectStatement s = parse("SELECT * FROM users ORDER BY age DESC, name, users.id ASC");
        assertEquals(List.of("age DESC", "name", "users.id"), s.getOrderBy().stream().map(OrderBy::toString).toList());
        assertEquals(List.of(true, false, false), s.getOrderBy().stream().map(OrderBy::descending).toList());
    }

    @Test
    void parsesLimitForms() {
        SelectStatement plain = parse("SELECT * FROM users LIMIT 3");
        assertEquals(3, plain.getLimit());
        assertEquals(0, plain.getOffset());
        SelectStatement withOffset = parse("SELECT * FROM users LIMIT 3 OFFSET 5");
        assertEquals(List.of(3, 5), List.of(withOffset.getLimit(), withOffset.getOffset()));
        // MySQL's LIMIT offset, count
        SelectStatement comma = parse("SELECT * FROM users LIMIT 5, 3");
        assertEquals(List.of(3, 5), List.of(comma.getLimit(), comma.getOffset()));
    }

    @Test
    void noOrderByOrLimitByDefault() {
        SelectStatement s = parse("SELECT * FROM users");
        assertTrue(s.getOrderBy().isEmpty());
        assertNull(s.getLimit());
        assertEquals(0, s.getOffset());
    }

    @Test
    void keywordsAreCaseInsensitiveAndClausesFollowWhere() {
        SelectStatement s = parse("select * from users u join orders o on u.id = o.user_id where o.total > 1 "
                + "order by o.total desc limit 1 offset 1;");
        assertEquals("o.total DESC", s.getOrderBy().get(0).toString());
        assertEquals(List.of(1, 1), List.of(s.getLimit(), s.getOffset()));
    }

    @Test
    void rejectsMalformedClauses() {
        String[] bad = {
                "ORDER name", "ORDER BY", "ORDER BY name,", "ORDER BY name DESC DESC", "ORDER BY 1",
                "LIMIT", "LIMIT -1", "LIMIT 1.5", "LIMIT 'a'", "LIMIT 1 OFFSET", "LIMIT 1,", "LIMIT 1 OFFSET 2, 3",
                "LIMIT 2 ORDER BY id", "ORDER BY id WHERE id = 1", "LIMIT 1 LIMIT 2",
        };
        for (String clause : bad) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users " + clause), clause);
        }
        assertEquals("Expected a whole number after LIMIT, got '-' at position 27",
                assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users LIMIT -1")).getMessage());
        assertEquals("LIMIT is too large: 99999999999",
                assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users LIMIT 99999999999")).getMessage());
    }

    @Test
    void descAscAndOffsetAreNotReserved() {
        run("CREATE TABLE t (desc INTEGER, asc INTEGER, offset INTEGER)");
        run("INSERT INTO t VALUES (1, 2, 3)");
        run("INSERT INTO t VALUES (2, 1, 3)");
        assertEquals(List.of(2, 1), select("SELECT desc FROM t ORDER BY desc desc").stream().map(r -> r.get("desc")).toList());
        assertEquals(List.of(2, 1), select("SELECT desc FROM t ORDER BY asc asc").stream().map(r -> r.get("desc")).toList());
    }

    // Sorting

    @Test
    void sortsEachKindOfValue() {
        assertEquals(List.of(2, 3, 1, 4), ids("SELECT id FROM users ORDER BY name"));
        assertEquals(List.of(2, 1, 3), ids("SELECT id FROM users WHERE age > 0 ORDER BY age, id"));
        assertEquals(List.of(1, 3, 2), ids("SELECT id FROM users WHERE score > 0 ORDER BY score"));
        assertEquals(List.of(2, 1, 3), ids("SELECT id FROM users WHERE active = TRUE OR active = FALSE ORDER BY active"));
    }

    @Test
    void descReversesTheOrder() {
        assertEquals(List.of(4, 1, 3, 2), ids("SELECT id FROM users ORDER BY name DESC"));
        assertEquals(List.of(4, 3, 2, 1), ids("SELECT id FROM users ORDER BY id DESC"));
    }

    @Test
    void tiesKeepTableOrder() {
        // Carol (1) and Bob (3) are both 30
        assertEquals(List.of(1, 3, 2), ids("SELECT id FROM users WHERE age > 0 ORDER BY age DESC"));
    }

    @Test
    void laterItemsBreakTies() {
        assertEquals(List.of(3, 1, 2), ids("SELECT id FROM users WHERE age > 0 ORDER BY age DESC, name"));
        assertEquals(List.of(1, 3, 2), ids("SELECT id FROM users WHERE age > 0 ORDER BY age DESC, name DESC"));
        assertEquals(List.of(2, 3, 1), ids("SELECT id FROM users WHERE age > 0 ORDER BY age, name"));
    }

    @Test
    void emptyCellsComeFirstAscendingAndLastDescending() {
        // Dan (4) has no age
        assertEquals(List.of(4, 2, 1, 3), ids("SELECT id FROM users ORDER BY age"));
        assertEquals(List.of(1, 3, 2, 4), ids("SELECT id FROM users ORDER BY age DESC"));
    }

    @Test
    void textSortsCaseSensitivelyLikeWhere() {
        run("INSERT INTO users (id, name) VALUES (5, 'adam')");
        assertEquals(List.of(2, 3, 1, 4, 5), ids("SELECT id FROM users ORDER BY name"));
    }

    @Test
    void canSortByAColumnThatIsNotSelected() {
        assertEquals(List.of(Map.of("name", "Alice"), Map.of("name", "Carol"), Map.of("name", "Bob"), Map.of("name", "Dan")),
                select("SELECT name FROM users ORDER BY score DESC"));
    }

    @Test
    void sortingDoesNotChangeTheTable() {
        select("SELECT * FROM users ORDER BY name");
        assertEquals(List.of(1, 2, 3, 4), ids("SELECT id FROM users"));
    }

    @Test
    void sortsJoinedRows() {
        assertEquals(List.of(
                Map.of("u.name", "Carol", "o.total", 20f),
                Map.of("u.name", "Carol", "o.total", 9.5f),
                Map.of("u.name", "Alice", "o.total", 5f)
        ), select("SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id ORDER BY o.total DESC"));
        assertEquals(List.of("Alice", "Carol", "Carol"),
                select("SELECT name FROM users u JOIN orders o ON u.id = o.user_id ORDER BY name, total")
                        .stream().map(r -> r.get("name")).toList());
    }

    @Test
    void leftJoinRowsWithoutAMatchSortLikeEmptyCells() {
        assertEquals(List.of("Bob", "Dan", "Alice", "Carol", "Carol"),
                select("SELECT name FROM users u LEFT JOIN orders o ON u.id = o.user_id ORDER BY o.total, u.id")
                        .stream().map(r -> r.get("name")).toList());
    }

    @Test
    void orderByChecksNames() {
        assertEquals("Unknown column 'nope'",
                assertThrows(IllegalArgumentException.class, () -> run("SELECT * FROM users ORDER BY nope")).getMessage());
        assertEquals("Column 'id' is ambiguous, write u.id or o.id",
                assertThrows(IllegalArgumentException.class,
                        () -> run("SELECT * FROM users u JOIN orders o ON u.id = o.user_id ORDER BY id")).getMessage());
        assertEquals("Unknown table 'x' in x.id",
                assertThrows(IllegalArgumentException.class, () -> run("SELECT * FROM users ORDER BY x.id")).getMessage());
    }

    // LIMIT and OFFSET

    @Test
    void limitKeepsTheFirstRows() {
        assertEquals(List.of(1, 2), ids("SELECT id FROM users LIMIT 2"));
        assertEquals(List.of(4, 3), ids("SELECT id FROM users ORDER BY id DESC LIMIT 2"));
        assertEquals(List.of(), ids("SELECT id FROM users LIMIT 0"));
        assertEquals(List.of(1, 2, 3, 4), ids("SELECT id FROM users LIMIT 100"));
    }

    @Test
    void offsetSkipsRows() {
        assertEquals(List.of(2, 3), ids("SELECT id FROM users ORDER BY id LIMIT 2 OFFSET 1"));
        assertEquals(List.of(2, 3), ids("SELECT id FROM users ORDER BY id LIMIT 1, 2"));
        assertEquals(List.of(4), ids("SELECT id FROM users ORDER BY id LIMIT 10 OFFSET 3"));
        assertEquals(List.of(), ids("SELECT id FROM users LIMIT 10 OFFSET 4"));
        assertEquals(List.of(), ids("SELECT id FROM users LIMIT 10 OFFSET 2000000000"));
        assertEquals(List.of(3, 4), ids("SELECT id FROM users LIMIT 2147483647 OFFSET 2"));
    }

    @Test
    void limitAppliesAfterWhereAndSort() {
        assertEquals(List.of(3), ids("SELECT id FROM users WHERE age = 30 ORDER BY name LIMIT 1"));
        assertEquals(List.of(1), ids("SELECT id FROM users WHERE age = 30 ORDER BY name LIMIT 1 OFFSET 1"));
    }

    @Test
    void limitCountsJoinedRows() {
        assertEquals(List.of(Map.of("o.id", 11)),
                select("SELECT o.id FROM users u JOIN orders o ON u.id = o.user_id ORDER BY o.total DESC LIMIT 1"));
    }

    @Test
    void printsSortedAndLimitedRows() {
        run("SELECT name FROM users ORDER BY name DESC LIMIT 2");
        assertEquals("[{name=Dan}, {name=Carol}]", output.toString().trim());
    }

    // EXPLAIN

    @Test
    void explainShowsSortAndLimit() {
        run("EXPLAIN SELECT * FROM users WHERE id = 1 ORDER BY name DESC, age LIMIT 5 OFFSET 2");
        assertEquals("users: index PRIMARY (id=1)\nsort by name DESC, age\nlimit 5 offset 2",
                output.toString().trim().replace("\r\n", "\n"));
    }

    @Test
    void explainLeavesOutClausesThatAreNotThere() {
        SelectStatement s = parse("SELECT * FROM users LIMIT 3");
        assertEquals(List.of("users: full scan", "limit 3"), engine.explain(s.getFrom(), s.getJoins(), s.getColumns(),
                s.getWhere(), s.getOrderBy(), s.getLimit(), s.getOffset()));
        assertEquals(List.of("users: full scan"), engine.explain(s.getFrom(), s.getJoins(), s.getColumns(), s.getWhere()));
    }
}
