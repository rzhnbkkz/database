package db.statement;

import db.model.Row;
import db.parser.Parser;
import db.query.Expression;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HavingMinMaxTest {
    private final PrintStream originalOut = System.out;
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        engine = new QueryEngine();
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR, city VARCHAR, age INTEGER, score FLOAT, active BOOLEAN)");
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total FLOAT)");
        run("INSERT INTO users VALUES (1, 'Alice', 'Oslo', 30, 7.5, TRUE)");
        run("INSERT INTO users VALUES (2, 'Bob', 'Rome', 25, 9, FALSE)");
        run("INSERT INTO users VALUES (3, 'Carol', 'Oslo', 41, 2.25, TRUE)");
        run("INSERT INTO users (id, name) VALUES (4, 'Dan')");
        run("INSERT INTO orders VALUES (10, 1, 9.5)");
        run("INSERT INTO orders VALUES (11, 1, 20)");
        run("INSERT INTO orders VALUES (12, 2, 5)");
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
        return engine.select(parse(sql).getQuery()).stream().map(Row::getData).toList();
    }

    private List<Object> cities(String having) {
        return select("SELECT city FROM users WHERE city != '' GROUP BY city HAVING " + having)
                .stream().map(r -> r.get("city")).toList();
    }

    private String fails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> run(sql)).getMessage();
    }

    // MIN and MAX

    @Test
    void minAndMaxOfEachType() {
        assertEquals(List.of(Map.of("MIN(age)", 25, "MAX(age)", 41, "MIN(score)", 2.25f, "MAX(score)", 9f,
                        "MIN(name)", "Alice", "MAX(name)", "Dan", "MIN(active)", false, "MAX(active)", true)),
                select("SELECT MIN(age), MAX(age), MIN(score), MAX(score), MIN(name), MAX(name), MIN(active), MAX(active) FROM users"));
    }

    @Test
    void minAndMaxSkipEmptyCells() {
        // Dan has no age, but MIN(age) isn't empty
        assertEquals(List.of(Map.of("MIN(age)", 25)), select("SELECT MIN(age) FROM users"));
    }

    @Test
    void minAndMaxOfNoValuesAreEmpty() {
        assertEquals(List.of(Map.of()), select("SELECT MIN(age), MAX(name) FROM users WHERE id = 99"));
        assertEquals(List.of(Map.of()), select("SELECT MAX(age) FROM users WHERE id = 4"));
    }

    @Test
    void minAndMaxPerGroup() {
        assertEquals(List.of(
                Map.of("city", "Oslo", "MIN(age)", 30, "MAX(name)", "Carol"),
                Map.of("city", "Rome", "MIN(age)", 25, "MAX(name)", "Bob"),
                Map.of("MAX(name)", "Dan")
        ), select("SELECT city, MIN(age), MAX(name) FROM users GROUP BY city"));
    }

    @Test
    void minAndMaxInJoinsAndOrderBy() {
        assertEquals(List.of(Map.of("u.name", "Bob", "MAX(o.total)", 5f), Map.of("u.name", "Alice", "MAX(o.total)", 20f)),
                select("SELECT u.name, MAX(o.total) FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.name ORDER BY MIN(o.total)"));
    }

    @Test
    void parsesMinAndMaxInAnyCase() {
        assertEquals(List.of("MIN(age)", "MAX(u.age)"),
                parse("SELECT min(age), Max(u.age) FROM users u").getColumns().stream().map(Object::toString).toList());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT MIN(*) FROM users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT MAX() FROM users"));
    }

    // HAVING: parsing

    @Test
    void parsesHaving() {
        SelectStatement s = parse("SELECT city FROM users GROUP BY city HAVING COUNT(*) > 1 AND MAX(age) >= AVG(age) "
                + "ORDER BY city LIMIT 1");
        assertEquals("COUNT(*)>1 AND MAX(age)>=AVG(age)", s.getHaving().toString());
        assertEquals(1, s.getLimit());
        assertSame(Expression.ALL_ROWS, parse("SELECT city FROM users GROUP BY city").getHaving());
    }

    @Test
    void rejectsMisplacedHaving() {
        String[] bad = {
                "SELECT city FROM users HAVING COUNT(*) > 1 GROUP BY city",
                "SELECT city FROM users GROUP BY city ORDER BY city HAVING COUNT(*) > 1",
                "SELECT city FROM users GROUP BY city HAVING",
                "SELECT city FROM users GROUP BY city HAVING COUNT(*)",
                "SELECT city FROM users GROUP BY city HAVING COUNT(*) > 1 HAVING COUNT(*) < 5",
        };
        for (String sql : bad) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(sql), sql);
        }
    }

    @Test
    void aggregatesStayOutOfWhereAndOn() {
        assertEquals("MAX(...) can't be used in WHERE or ON, use HAVING to filter on an aggregate",
                assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users WHERE MAX(age) > 1")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users WHERE age > MAX(age)"));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT * FROM users u JOIN orders o ON COUNT(*) > 1"));
    }

    // HAVING: results

    @Test
    void havingKeepsGroupsThatMatch() {
        assertEquals(List.of("Oslo"), cities("COUNT(*) > 1"));
        assertEquals(List.of("Oslo", "Rome"), cities("COUNT(*) >= 1"));
        assertEquals(List.of(), cities("COUNT(*) > 5"));
    }

    @Test
    void havingComparesAggregatesWithEachOther() {
        // Oslo: 41 > 35.5; Rome has one person, so MAX = AVG
        assertEquals(List.of("Oslo"), cities("MAX(age) > AVG(age)"));
        assertEquals(List.of("Rome"), cities("MAX(age) = MIN(age)"));
    }

    @Test
    void havingCanUseGroupColumns() {
        assertEquals(List.of("Rome"), cities("city = 'Rome'"));
        assertEquals(List.of("Rome"), cities("COUNT(*) = 1 AND city != 'Oslo'"));
    }

    @Test
    void havingSupportsOrNotAndParentheses() {
        assertEquals(List.of("Oslo", "Rome"), cities("COUNT(*) > 1 OR MIN(age) < 26"));
        assertEquals(List.of("Rome"), cities("NOT COUNT(*) > 1"));
        assertEquals(List.of("Oslo"), cities("(SUM(age) > 60 OR city = 'Paris') AND NOT MIN(age) < 30"));
    }

    @Test
    void emptyAggregateIsUnknownInHaving() {
        // The group of people without a city has no ages, so MIN(age) is empty and never compares
        assertEquals(List.of(Map.of("COUNT(*)", 2), Map.of("COUNT(*)", 1)),
                select("SELECT COUNT(*) FROM users GROUP BY city HAVING MIN(age) > 0 OR MIN(age) <= 0"));
        assertEquals(2, select("SELECT COUNT(*) FROM users GROUP BY city HAVING NOT MIN(age) > 100").size());
    }

    @Test
    void havingAggregateNeedNotBeSelected() {
        assertEquals(List.of(Map.of("city", "Oslo")),
                select("SELECT city FROM users WHERE age > 0 GROUP BY city HAVING SUM(score) > 9.5"));
    }

    @Test
    void havingWithoutGroupByTreatsTheTableAsOneGroup() {
        assertEquals(List.of(Map.of("COUNT(*)", 4)), select("SELECT COUNT(*) FROM users HAVING COUNT(*) > 1"));
        assertEquals(List.of(), select("SELECT COUNT(*) FROM users HAVING COUNT(*) > 10"));
    }

    @Test
    void havingRunsBeforeOrderByAndLimit() {
        assertEquals(List.of(Map.of("u.name", "Bob", "SUM(o.total)", 5f), Map.of("u.name", "Alice", "SUM(o.total)", 29.5f)),
                select("SELECT u.name, SUM(o.total) FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.name "
                        + "HAVING SUM(o.total) >= 10 OR u.name = 'Bob' ORDER BY SUM(o.total)"));
        assertEquals(List.of(Map.of("city", "Rome")),
                select("SELECT city FROM users WHERE age > 0 GROUP BY city HAVING COUNT(*) >= 1 ORDER BY city DESC LIMIT 1"));
    }

    @Test
    void havingRunsAfterWhere() {
        // WHERE drops Carol first, so Oslo has one person left
        assertEquals(List.of(Map.of("city", "Oslo", "COUNT(*)", 1), Map.of("city", "Rome", "COUNT(*)", 1)),
                select("SELECT city, COUNT(*) FROM users WHERE age < 40 GROUP BY city HAVING COUNT(*) = 1"));
    }

    // HAVING: name checks

    @Test
    void havingColumnsMustBeGrouped() {
        assertEquals("Column 'age' must be in GROUP BY, or inside an aggregate like COUNT or MAX",
                fails("SELECT city FROM users GROUP BY city HAVING age > 30"));
        assertEquals("Column 'name' must be in GROUP BY, or inside an aggregate like COUNT or MAX",
                fails("SELECT COUNT(*) FROM users HAVING name = 'Bob'"));
    }

    @Test
    void havingChecksNamesAndTypes() {
        assertEquals("Unknown column 'nope'", fails("SELECT city FROM users GROUP BY city HAVING MAX(nope) > 1"));
        assertEquals("SUM needs a number column, 'name' is VARCHAR", fails("SELECT city FROM users GROUP BY city HAVING SUM(name) > 1"));
        assertEquals("Unknown column 'Bob' (to compare with text, put it in quotes: 'Bob')",
                fails("SELECT city FROM users GROUP BY city HAVING MAX(name) = Bob"));
    }

    @Test
    void havingMakesTheQueryGrouped() {
        assertEquals("SELECT * can't be used with GROUP BY or aggregates, list the columns instead",
                fails("SELECT * FROM users HAVING COUNT(*) > 1"));
    }

    // EXPLAIN

    @Test
    void explainShowsHaving() {
        assertEquals(List.of("users: full scan", "group by city", "having COUNT(*)>1", "sort by city"),
                engine.explain(parse("SELECT city, COUNT(*) FROM users GROUP BY city HAVING COUNT(*) > 1 ORDER BY city").getQuery()));
        assertEquals(List.of("users: full scan", "group by city"),
                engine.explain(parse("SELECT city, COUNT(*) FROM users GROUP BY city").getQuery()));
    }
}
