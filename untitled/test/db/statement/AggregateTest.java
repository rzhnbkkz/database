package db.statement;

import db.model.Row;
import db.parser.Parser;
import db.query.Aggregate;
import db.query.ColumnRef;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AggregateTest {
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(output));
        engine = new QueryEngine();
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR, city VARCHAR, age INTEGER, active BOOLEAN)");
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total FLOAT)");
        run("INSERT INTO users VALUES (1, 'Alice', 'Oslo', 30, TRUE)");
        run("INSERT INTO users VALUES (2, 'Bob', 'Rome', 25, FALSE)");
        run("INSERT INTO users VALUES (3, 'Carol', 'Oslo', 41, TRUE)");
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
        return engine.select(parse(sql).getQuery()).stream().map(Row::getData).toList();
    }

    private String fails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> run(sql)).getMessage();
    }

    // Parsing

    @Test
    void parsesAggregatesInTheSelectList() {
        SelectStatement s = parse("SELECT city, COUNT(*), count(age), Sum(u.age), AVG(age) FROM users u");
        assertEquals(List.of("city", "COUNT(*)", "COUNT(age)", "SUM(u.age)", "AVG(age)"),
                s.getColumns().stream().map(Object::toString).toList());
        assertEquals(Aggregate.countAll(), s.getColumns().get(1));
        assertEquals(new Aggregate(Aggregate.Function.SUM, new ColumnRef("u", "age")), s.getColumns().get(3));
    }

    @Test
    void parsesGroupByAndAggregateOrderBy() {
        SelectStatement s = parse("SELECT city, COUNT(*) FROM users WHERE age > 1 GROUP BY city, active "
                + "ORDER BY COUNT(*) DESC, city LIMIT 2");
        assertEquals(List.of("city", "active"), s.getGroupBy().stream().map(Object::toString).toList());
        assertEquals(List.of("COUNT(*) DESC", "city"), s.getOrderBy().stream().map(Object::toString).toList());
        assertEquals(2, s.getLimit());
    }

    @Test
    void rejectsMalformedAggregates() {
        String[] bad = {
                "SELECT COUNT( FROM users", "SELECT COUNT() FROM users", "SELECT COUNT(*, id) FROM users",
                "SELECT SUM(*) FROM users", "SELECT AVG(*) FROM users", "SELECT COUNT(u.*) FROM users u",
                "SELECT COUNT(id FROM users", "SELECT COUNT(*) FROM users GROUP city", "SELECT COUNT(*) FROM users GROUP BY",
                "SELECT COUNT(*) FROM users GROUP BY city,", "SELECT COUNT(*) FROM users ORDER BY id GROUP BY id",
                "SELECT COUNT(COUNT(id)) FROM users",
        };
        for (String sql : bad) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(sql), sql);
        }
    }

    @Test
    void unknownFunctionsAreNamed() {
        assertEquals("Unknown function MEDIAN, expected an aggregate (COUNT, SUM, AVG, MIN, MAX) or one of UPPER, LOWER, LENGTH, "
                        + "TRIM, CONCAT, SUBSTRING, REPLACE, ABS, ROUND, FLOOR, CEIL, COALESCE, IFNULL, NULLIF",
                assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT MEDIAN(age) FROM users")).getMessage());
    }

    @Test
    void aggregatesAreNotAllowedInConditions() {
        assertEquals("COUNT(...) can't be used in WHERE or ON, use HAVING to filter on an aggregate",
                assertThrows(IllegalArgumentException.class,
                        () -> Parser.parse("SELECT * FROM users WHERE COUNT(*) > 1")).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("SELECT * FROM users u JOIN orders o ON SUM(o.total) > 1"));
    }

    @Test
    void functionNamesStillWorkAsColumnNames() {
        run("CREATE TABLE stats (count INTEGER, sum INTEGER)");
        run("INSERT INTO stats VALUES (2, 7)");
        run("INSERT INTO stats VALUES (3, 7)");
        assertEquals(List.of(Map.of("sum", 7, "SUM(count)", 5, "COUNT(sum)", 2)),
                select("SELECT sum, SUM(count), COUNT(sum) FROM stats WHERE count > 0 GROUP BY sum"));
    }

    // Aggregates over the whole table

    @Test
    void countSumAndAvgOverAllRows() {
        assertEquals(List.of(Map.of("COUNT(*)", 4, "COUNT(age)", 3, "SUM(age)", 96, "AVG(age)", 32f)),
                select("SELECT COUNT(*), COUNT(age), SUM(age), AVG(age) FROM users"));
    }

    @Test
    void floatColumnsGiveFloatResults() {
        assertEquals(List.of(Map.of("SUM(total)", 34.5f, "AVG(total)", 11.5f)), select("SELECT SUM(total), AVG(total) FROM orders"));
    }

    @Test
    void averageOfWholeNumbersCanHaveAFraction() {
        assertEquals(List.of(Map.of("AVG(age)", 27.5f)), select("SELECT AVG(age) FROM users WHERE age < 35"));
    }

    @Test
    void countCountsAnyTypeOfColumn() {
        assertEquals(List.of(Map.of("COUNT(name)", 4, "COUNT(city)", 3, "COUNT(active)", 3)),
                select("SELECT COUNT(name), COUNT(city), COUNT(active) FROM users"));
    }

    @Test
    void aggregatesRespectWhere() {
        assertEquals(List.of(Map.of("COUNT(*)", 2, "SUM(age)", 71)), select("SELECT COUNT(*), SUM(age) FROM users WHERE city = 'Oslo'"));
    }

    @Test
    void noRowsGiveCountZeroAndEmptySumAndAvg() {
        assertEquals(List.of(Map.of("COUNT(*)", 0, "COUNT(age)", 0)),
                select("SELECT COUNT(*), COUNT(age), SUM(age), AVG(age) FROM users WHERE id = 99"));
        assertEquals("[{}]", printed("SELECT SUM(age) FROM users WHERE id = 99"));
    }

    @Test
    void sumTooBigForIntegerStaysExact() {
        run("CREATE TABLE big (n INTEGER)");
        run("INSERT INTO big VALUES (2000000000)");
        run("INSERT INTO big VALUES (2000000000)");
        assertEquals(List.of(Map.of("SUM(n)", 4000000000L, "AVG(n)", 2e9f)), select("SELECT SUM(n), AVG(n) FROM big"));
    }

    @Test
    void sumAndAvgNeedNumberColumns() {
        assertEquals("SUM needs a number column, 'name' is VARCHAR", fails("SELECT SUM(name) FROM users"));
        assertEquals("AVG needs a number column, 'active' is BOOLEAN", fails("SELECT AVG(active) FROM users"));
        assertEquals("Unknown column 'nope'", fails("SELECT COUNT(nope) FROM users"));
    }

    // GROUP BY

    @Test
    void groupsInOrderOfFirstAppearance() {
        Map<String, Object> noCity = new HashMap<>(Map.of("COUNT(*)", 1));
        assertEquals(List.of(
                Map.of("city", "Oslo", "COUNT(*)", 2, "AVG(age)", 35.5f),
                Map.of("city", "Rome", "COUNT(*)", 1, "AVG(age)", 25f),
                noCity
        ), select("SELECT city, COUNT(*), AVG(age) FROM users GROUP BY city"));
    }

    @Test
    void emptyCellsFormTheirOwnGroup() {
        run("INSERT INTO users (id, name) VALUES (5, 'Eve')");
        assertEquals(2, select("SELECT city, COUNT(*) FROM users GROUP BY city").get(2).get("COUNT(*)"));
    }

    @Test
    void groupByWithoutAggregatesListsDistinctValues() {
        assertEquals(List.of(Map.of("city", "Oslo"), Map.of("city", "Rome"), Map.of()),
                select("SELECT city FROM users GROUP BY city"));
    }

    @Test
    void groupBySeveralColumns() {
        run("INSERT INTO users VALUES (5, 'Eve', 'Oslo', 50, FALSE)");
        assertEquals(List.of(
                Map.of("city", "Oslo", "active", true, "COUNT(*)", 2),
                Map.of("city", "Rome", "active", false, "COUNT(*)", 1),
                Map.of("city", "Oslo", "active", false, "COUNT(*)", 1)
        ), select("SELECT city, active, COUNT(*) FROM users WHERE age > 0 GROUP BY city, active"));
    }

    @Test
    void groupedColumnNeedNotBeSelected() {
        assertEquals(List.of(Map.of("COUNT(*)", 2), Map.of("COUNT(*)", 1)), select("SELECT COUNT(*) FROM users WHERE age > 0 GROUP BY city"));
    }

    @Test
    void groupsJoinedRows() {
        assertEquals(List.of(
                Map.of("u.name", "Alice", "COUNT(o.id)", 2, "SUM(o.total)", 29.5f),
                Map.of("u.name", "Bob", "COUNT(o.id)", 1, "SUM(o.total)", 5f),
                Map.of("u.name", "Carol", "COUNT(o.id)", 0),
                Map.of("u.name", "Dan", "COUNT(o.id)", 0)
        ), select("SELECT u.name, COUNT(o.id), SUM(o.total) FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.name"));
    }

    @Test
    void unqualifiedNamesWorkInAJoinWhenUnambiguous() {
        assertEquals(List.of(Map.of("name", "Alice", "SUM(total)", 29.5f), Map.of("name", "Bob", "SUM(total)", 5f)),
                select("SELECT name, SUM(total) FROM users u JOIN orders o ON u.id = o.user_id GROUP BY name"));
    }

    // ORDER BY and LIMIT on groups

    @Test
    void orderByAnAggregate() {
        assertEquals(List.of("Alice", "Bob", "Carol", "Dan"), select(
                "SELECT u.name FROM users u LEFT JOIN orders o ON u.id = o.user_id GROUP BY u.name ORDER BY COUNT(o.id) DESC, u.name")
                .stream().map(r -> r.get("u.name")).toList());
        // The aggregate in ORDER BY doesn't have to be selected
        assertEquals(List.of(Map.of("city", "Rome"), Map.of("city", "Oslo")),
                select("SELECT city FROM users WHERE age > 0 GROUP BY city ORDER BY AVG(age)"));
    }

    @Test
    void orderByAGroupColumnAndLimit() {
        assertEquals(List.of(Map.of("city", "Rome", "COUNT(*)", 1)),
                select("SELECT city, COUNT(*) FROM users WHERE age > 0 GROUP BY city ORDER BY city DESC LIMIT 1"));
        assertEquals(List.of(Map.of("city", "Rome", "COUNT(*)", 1)),
                select("SELECT city, COUNT(*) FROM users WHERE age > 0 GROUP BY city LIMIT 1 OFFSET 1"));
        assertEquals(List.of(), select("SELECT COUNT(*) FROM users LIMIT 0"));
    }

    @Test
    void printsGroupedRows() {
        assertEquals("[{city=Oslo, COUNT(*)=2}, {city=Rome, COUNT(*)=1}]",
                printed("SELECT city, COUNT(*) FROM users WHERE age > 0 GROUP BY city"));
    }

    // What can be selected once rows are grouped

    @Test
    void plainColumnsMustBeGrouped() {
        assertEquals("Column 'name' must be in GROUP BY, or inside an aggregate like COUNT or MAX", fails("SELECT name, COUNT(*) FROM users"));
        assertEquals("Column 'name' must be in GROUP BY, or inside an aggregate like COUNT or MAX", fails("SELECT name, city FROM users GROUP BY city"));
        assertEquals("Column 'age' must be in GROUP BY, or inside an aggregate like COUNT or MAX",
                fails("SELECT city FROM users GROUP BY city ORDER BY age"));
        assertEquals("Column 'u.id' must be in GROUP BY, or inside an aggregate like COUNT or MAX",
                fails("SELECT u.* FROM users u JOIN orders o ON u.id = o.user_id GROUP BY u.name"));
    }

    @Test
    void sameColumnWrittenDifferentlyCountsAsGrouped() {
        assertEquals(List.of(Map.of("users.city", "Oslo"), Map.of("users.city", "Rome")),
                select("SELECT users.city FROM users WHERE age > 0 GROUP BY city"));
    }

    @Test
    void starCannotBeGrouped() {
        assertEquals("SELECT * can't be used with GROUP BY or aggregates, list the columns instead",
                fails("SELECT * FROM users GROUP BY city"));
        assertEquals("SELECT * can't be used with GROUP BY or aggregates, list the columns instead",
                fails("SELECT * FROM users ORDER BY COUNT(*)"));
    }

    @Test
    void groupByChecksNames() {
        assertEquals("Unknown column 'nope'", fails("SELECT COUNT(*) FROM users GROUP BY nope"));
        assertEquals("Column 'id' is ambiguous, write u.id or o.id",
                fails("SELECT COUNT(*) FROM users u JOIN orders o ON u.id = o.user_id GROUP BY id"));
    }

    @Test
    void groupingDoesNotChangeTheTable() {
        select("SELECT city, COUNT(*) FROM users GROUP BY city");
        assertEquals(4, engine.getTable("users").getRows().size());
    }

    // EXPLAIN

    @Test
    void explainShowsGrouping() {
        assertEquals(List.of("users: index PRIMARY (id=1)", "group by city", "sort by COUNT(*) DESC", "limit 3"),
                engine.explain(parse("SELECT city, COUNT(*) FROM users WHERE id = 1 GROUP BY city ORDER BY COUNT(*) DESC LIMIT 3").getQuery()));
        assertEquals(List.of("users: full scan", "aggregate all rows into one"),
                engine.explain(parse("SELECT COUNT(*) FROM users").getQuery()));
    }

    private String printed(String sql) {
        output.reset();
        run(sql);
        return output.toString().trim();
    }
}
