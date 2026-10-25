package db.statement;

import db.model.Row;
import db.parser.Parser;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

// IN (SELECT ...), EXISTS, and (SELECT ...) as a value, including subqueries that use the outer query's columns
class SubqueryTest {
    private final PrintStream originalOut = System.out;
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        engine = new QueryEngine();
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR, city VARCHAR)");
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total FLOAT)");
        run("INSERT INTO users VALUES (1, 'Alice', 'Oslo'), (2, 'Bob', 'Rome'), (3, 'Carol', 'Oslo'), (4, 'Dan', NULL)");
        run("INSERT INTO orders VALUES (10, 1, 9.5), (11, 1, 20), (12, 2, 5), (13, NULL, 7)");
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private void run(String sql) {
        Parser.parse(sql).execute(engine);
    }

    private List<Map<String, Object>> select(String sql) {
        return engine.select(((SelectStatement) Parser.parse(sql)).getQuery()).stream().map(Row::getData).toList();
    }

    private List<Object> names(String where) {
        return select("SELECT name FROM users u WHERE " + where).stream().map(r -> r.get("name")).toList();
    }

    private String fails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> run(sql)).getMessage();
    }

    private String parseFails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> Parser.parse(sql)).getMessage();
    }

    // IN (SELECT ...)

    @Test
    void inSubquery() {
        assertEquals(List.of("Alice", "Bob"), names("id IN (SELECT user_id FROM orders)"));
        assertEquals(List.of("Alice"), names("id IN (SELECT user_id FROM orders WHERE total > 9)"));
        assertEquals(List.of("Carol", "Dan"), names("id NOT IN (SELECT user_id FROM orders WHERE user_id IS NOT NULL)"));
    }

    @Test
    void notInFollowsSqlNullRules() {
        // Order 13 has no user, so NOT IN is unknown for everyone who isn't in the list
        assertEquals(List.of(), names("id NOT IN (SELECT user_id FROM orders)"));
        // Dan has no city: unknown for IN and NOT IN alike
        assertEquals(List.of("Alice", "Carol"), names("city IN (SELECT city FROM users WHERE id = 1)"));
        assertEquals(List.of("Bob"), names("city NOT IN (SELECT city FROM users WHERE id = 1)"));
    }

    @Test
    void anEmptySubqueryIsFalseForInEvenForEmptyValues() {
        assertEquals(List.of(), names("city IN (SELECT city FROM users WHERE id = 99)"));
        assertEquals(List.of("Alice", "Bob", "Carol", "Dan"), names("city NOT IN (SELECT city FROM users WHERE id = 99)"));
    }

    @Test
    void inComparesNumbersByValue() {
        // total - 3 is 6.5, 17, 2 and 4
        assertEquals(List.of("Bob", "Dan"), names("id IN (SELECT total - 3 FROM orders)"));
        assertEquals(List.of(), names("name IN (SELECT total FROM orders)"));
    }

    // EXISTS

    @Test
    void existsWithTheOuterRow() {
        assertEquals(List.of("Alice", "Bob"), names("EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)"));
        assertEquals(List.of("Alice"), names("EXISTS (SELECT * FROM orders WHERE user_id = u.id AND total > 8)"));
        assertEquals(List.of("Carol", "Dan"), names("NOT EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id)"));
    }

    @Test
    void existsWithoutTheOuterRow() {
        assertEquals(4, names("EXISTS (SELECT 1 FROM orders WHERE total > 10)").size());
        assertEquals(0, names("EXISTS (SELECT 1 FROM orders WHERE total > 100)").size());
        // Grouped subqueries count their groups: one empty group still makes a row
        assertEquals(4, names("EXISTS (SELECT COUNT(*) FROM orders WHERE total > 100)").size());
    }

    @Test
    void existsCombinesWithOtherConditions() {
        assertEquals(List.of("Alice", "Carol"),
                names("city = 'Oslo' AND (EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id) OR u.id = 3)"));
    }

    // (SELECT ...) as a value

    @Test
    void valueSubqueries() {
        assertEquals(List.of("Alice"), names("id = (SELECT user_id FROM orders ORDER BY total DESC LIMIT 1)"));
        assertEquals(List.of(Map.of("id", 11, "total", 20f)), select("SELECT id, total FROM orders WHERE total > (SELECT AVG(total) FROM orders)"));
        assertEquals(List.of(Map.of("name", "Alice", "n", 2), Map.of("name", "Bob", "n", 1), Map.of("name", "Carol", "n", 0), Map.of("name", "Dan", "n", 0)),
                select("SELECT name, (SELECT COUNT(*) FROM orders o WHERE o.user_id = u.id) AS n FROM users u"));
    }

    @Test
    void noRowsGiveAnEmptyValueAndMoreThanOneIsAnError() {
        assertEquals(List.of(Map.of("name", "Carol")), select("SELECT name, (SELECT MAX(total) FROM orders WHERE user_id = u.id) AS top FROM users u WHERE id = 3"));
        assertEquals("Subquery returned 4 rows, expected at most 1: (SELECT user_id FROM orders)",
                fails("SELECT name FROM users WHERE id = (SELECT user_id FROM orders)"));
    }

    @Test
    void valueSubqueriesInExpressionsOrderByAndHaving() {
        assertEquals(List.of(Map.of("share", 0.5f)), select("SELECT 1 / (SELECT COUNT(*) FROM users WHERE city = 'Oslo') AS share FROM users WHERE id = 1"));
        assertEquals(List.of("Alice", "Bob", "Carol", "Dan"), select("SELECT name FROM users u ORDER BY (SELECT COUNT(*) FROM orders WHERE user_id = u.id) DESC, id")
                .stream().map(r -> r.get("name")).toList());
        assertEquals(List.of(Map.of("city", "Oslo")),
                select("SELECT city FROM users GROUP BY city HAVING COUNT(*) > (SELECT COUNT(*) FROM users WHERE city = 'Rome')"));
    }

    @Test
    void subqueryLabelIsItsSql() {
        assertEquals(List.of(Map.of("(SELECT MAX(total) FROM orders)", 20f)), select("SELECT (SELECT MAX(total) FROM orders) FROM users WHERE id = 1"));
    }

    // Which names belong to the subquery and which to the outer query

    @Test
    void theSubquerysOwnColumnsWin() {
        // Inside the subquery, id is orders.id, not users.id, so no order id matches a user
        assertEquals(List.of(), names("EXISTS (SELECT 1 FROM orders WHERE id = 1)"));
        // A table alias or name points at the outer query when the subquery doesn't have it
        assertEquals(List.of("Alice", "Bob"), names("EXISTS (SELECT 1 FROM orders WHERE user_id = u.id)"));
        assertEquals(List.of("Alice", "Bob"), select("SELECT name FROM users WHERE EXISTS (SELECT 1 FROM orders WHERE user_id = users.id)")
                .stream().map(r -> r.get("name")).toList());
    }

    @Test
    void outerColumnsWorkInNestedSubqueries() {
        // Users with an order placed by someone from their own city
        assertEquals(List.of("Alice", "Bob"), names("EXISTS (SELECT 1 FROM orders o WHERE o.user_id = u.id "
                + "AND EXISTS (SELECT 1 FROM users x WHERE x.id = o.user_id AND x.city = u.city))"));
    }

    @Test
    void namesAreCheckedBeforeAnyRowRuns() {
        run("DELETE FROM users");
        assertEquals("Unknown column 'o.nope'", fails("SELECT name FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.nope = u.id)"));
        assertEquals("Unknown column 'nope'", fails("SELECT name FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE nope = 1)"));
        assertEquals("Table 'nope' does not exist", fails("SELECT name FROM users u WHERE id IN (SELECT id FROM nope)"));
        assertEquals("Unknown table 'x' in x.id", fails("SELECT name FROM users u WHERE EXISTS (SELECT 1 FROM orders o WHERE o.user_id = x.id)"));
    }

    @Test
    void outerAliasesAreNotTheSubquerys() {
        // n is an alias of the outer query, not a column the subquery can see
        assertEquals("Unknown column 'n'", fails("SELECT COUNT(*) AS n FROM users WHERE id IN (SELECT user_id FROM orders ORDER BY n)"));
        // and the subquery's own alias works inside it (DESC, as the order without a user would come first)
        assertEquals(List.of("Bob"), names("id = (SELECT user_id AS who FROM orders ORDER BY who DESC LIMIT 1)"));
    }

    @Test
    void groupedQueriesCheckTheOuterColumnsTheirSubqueriesUse() {
        assertEquals(List.of(Map.of("city", "Oslo", "n", 2), Map.of("city", "Rome", "n", 1), Map.of("n", 0)),
                select("SELECT city, (SELECT COUNT(*) FROM orders o JOIN users x ON x.id = o.user_id WHERE x.city = u.city) AS n "
                        + "FROM users u GROUP BY city"));
        assertEquals("Column 'id' must be in GROUP BY, or inside an aggregate like COUNT or MAX",
                fails("SELECT city, (SELECT COUNT(*) FROM orders WHERE user_id = u.id) FROM users u GROUP BY city"));
    }

    // Writing

    @Test
    void updateAndDeleteWithSubqueries() {
        run("UPDATE users SET name = CONCAT(name, '*') WHERE id IN (SELECT user_id FROM orders WHERE total > 8)");
        assertEquals(List.of("Alice*", "Bob", "Carol", "Dan"), names("id > 0"));
        run("DELETE FROM users WHERE NOT EXISTS (SELECT 1 FROM orders WHERE user_id = users.id)");
        assertEquals(List.of("Alice*", "Bob"), names("id > 0"));
    }

    @Test
    void setFromACorrelatedSubquery() {
        run("ALTER TABLE users ADD COLUMN spent FLOAT DEFAULT 0");
        run("UPDATE users SET spent = COALESCE((SELECT SUM(total) FROM orders WHERE user_id = users.id), 0)");
        assertEquals(List.of(29.5f, 5f, 0f, 0f), select("SELECT spent FROM users").stream().map(r -> r.get("spent")).toList());
    }

    @Test
    void subqueriesOnTheTableBeingChangedSeeItAsItWas() {
        // Every row is compared with the maximum before the update, not one that changes along the way
        run("UPDATE users SET id = id + (SELECT MAX(id) FROM users)");
        assertEquals(List.of(5, 6, 7, 8), select("SELECT id FROM users").stream().map(r -> r.get("id")).toList());
        run("DELETE FROM users WHERE id IN (SELECT id FROM users WHERE city = 'Oslo')");
        assertEquals(List.of("Bob", "Dan"), names("id > 0"));
    }

    @Test
    void valuesCantUseSubqueries() {
        assertEquals("VALUES can't use subqueries, got (SELECT MAX(id) FROM users) + 1",
                parseFails("INSERT INTO users VALUES ((SELECT MAX(id) FROM users) + 1, 'Eve', NULL)"));
    }

    // Parsing

    @Test
    void parseErrors() {
        assertEquals("A subquery after IN must select one column, got 2", parseFails("SELECT * FROM users WHERE id IN (SELECT id, user_id FROM orders)"));
        assertEquals("A subquery after IN must select one column, got *", parseFails("SELECT * FROM users WHERE id IN (SELECT * FROM orders)"));
        assertEquals("A subquery used as a value must select one column, got 2", parseFails("SELECT (SELECT id, total FROM orders) FROM users"));
        for (String bad : new String[]{"EXISTS SELECT 1 FROM orders", "EXISTS (SELECT 1 FROM orders", "EXISTS ()", "id IN (SELECT id FROM)",
                "id = (SELECT id FROM orders"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users WHERE " + bad), bad);
        }
    }

    @Test
    void existsIsReservedButIfExistsStillWorks() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE t (exists INTEGER)"));
        run("DROP TABLE IF EXISTS nothing");
        run("CREATE TABLE IF NOT EXISTS users (x INTEGER)");
    }

    @Test
    void subqueriesInParenthesesAndGroups() {
        assertEquals(List.of("Alice", "Bob"), names("(id IN (SELECT user_id FROM orders))"));
        assertEquals(List.of("Alice"), names("((SELECT COUNT(*) FROM orders WHERE user_id = u.id) > 1)"));
        assertEquals(List.of("Alice"), names("(EXISTS (SELECT 1 FROM orders WHERE user_id = u.id) AND id = 1)"));
    }

    // Against JOIN, on random data

    @Test
    void existsInAndJoinAgreeOnRandomData() {
        Random random = new Random(21);
        for (boolean indexed : new boolean[]{false, true}) {
            engine = new QueryEngine();
            run("CREATE TABLE a (id INTEGER PRIMARY KEY, g INTEGER)");
            run("CREATE TABLE b (id INTEGER PRIMARY KEY, a_id INTEGER, v INTEGER)");
            if (indexed) run("CREATE INDEX idx_a_id ON b (a_id)");
            for (int i = 0; i < 60; i++) run("INSERT INTO a VALUES (" + i + ", " + random.nextInt(5) + ")");
            for (int i = 0; i < 150; i++) {
                String aId = random.nextInt(8) == 0 ? "NULL" : String.valueOf(random.nextInt(70));
                run("INSERT INTO b VALUES (" + i + ", " + aId + ", " + random.nextInt(10) + ")");
            }
            for (int v = 0; v < 10; v++) {
                List<Map<String, Object>> viaJoin = select("SELECT DISTINCT a.id FROM a JOIN b ON b.a_id = a.id WHERE b.v > " + v);
                List<Map<String, Object>> viaExists = select("SELECT a.id FROM a WHERE EXISTS (SELECT 1 FROM b WHERE b.a_id = a.id AND b.v > " + v + ")");
                List<Map<String, Object>> viaIn = select("SELECT a.id FROM a WHERE id IN (SELECT a_id FROM b WHERE v > " + v + ")");
                assertEquals(viaJoin, viaExists, "EXISTS, v > " + v);
                assertEquals(viaJoin.stream().map(r -> r.get("a.id")).toList(), viaIn.stream().map(r -> r.get("a.id")).toList(), "IN, v > " + v);
                // NOT EXISTS is everything else; NOT IN is empty as soon as b has an empty a_id
                List<Object> notExists = select("SELECT a.id FROM a WHERE NOT EXISTS (SELECT 1 FROM b WHERE b.a_id = a.id AND b.v > " + v + ")")
                        .stream().map(r -> r.get("a.id")).toList();
                assertEquals(60, notExists.size() + viaJoin.size());
                assertTrue(notExists.stream().noneMatch(viaJoin.stream().map(r -> r.get("a.id")).toList()::contains));
            }
        }
    }
}
