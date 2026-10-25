package db.statement;

import db.model.Row;
import db.parser.Parser;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

// Arithmetic, functions and CASE in SELECT, WHERE, GROUP BY, HAVING, ORDER BY, UPDATE ... SET and VALUES
class ExpressionsTest {
    private final PrintStream originalOut = System.out;
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        engine = new QueryEngine();
        run("CREATE TABLE items (id INTEGER PRIMARY KEY, name VARCHAR, price FLOAT, qty INTEGER, city VARCHAR)");
        run("INSERT INTO items VALUES (1, 'pen', 1.5, 10, 'Oslo'), (2, 'cup', 4, 3, 'oslo'), (3, 'book', 12.25, 2, 'Rome'), "
                + "(4, 'box', NULL, 7, NULL)");
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

    // The single value of a one-column query on item 1
    private Object value(String expression) {
        return select("SELECT " + expression + " AS v FROM items WHERE id = 1").get(0).get("v");
    }

    private List<Object> ids(String where) {
        return select("SELECT id FROM items WHERE " + where).stream().map(r -> r.get("id")).toList();
    }

    private String fails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> run(sql)).getMessage();
    }

    private String parseFails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> Parser.parse(sql)).getMessage();
    }

    // Arithmetic

    @Test
    void precedenceAndParentheses() {
        assertEquals(7, value("1 + 2 * 3"));
        assertEquals(9, value("(1 + 2) * 3"));
        assertEquals(5, value("10 - 3 - 2"));
        assertEquals(-6, value("2 * -3"));
        assertEquals(1, value("-(2 - 3)"));
        assertEquals(1, value("7 % 3"));
        assertEquals(-1, value("-7 % 3"));
    }

    @Test
    void writtenWithParenthesesOnlyWhereNeeded() {
        assertEquals(List.of("price * qty + 1", "price * (qty + 1)", "qty - (qty - 1)", "qty - qty - 1", "-(qty + 1)", "-qty"),
                parse("SELECT price * qty + 1, price * (qty + 1), qty - (qty - 1), (qty - qty) - 1, -(qty + 1), -qty FROM items")
                        .getColumns().stream().map(Object::toString).toList());
    }

    @Test
    void wholeNumbersStayWholeExceptDivision() {
        assertEquals(30, value("qty * 3"));
        assertEquals(2.5f, value("qty / 4"));
        assertEquals(5f, value("qty / 2"));
        assertEquals(15f, value("price * qty"));
        assertEquals(3.5f, value("price + 2"));
        assertEquals(1.5f, value("price % 2"));
    }

    @Test
    void emptyValuesAndDivisionByZeroGiveEmpty() {
        assertNull(select("SELECT price * 2 AS v FROM items WHERE id = 4").get(0).get("v"));
        assertNull(value("qty / 0"));
        assertNull(value("qty % 0"));
        assertNull(value("1 + NULL"));
    }

    @Test
    void wholeNumbersBeyondIntegerAreExactUntilTheyOverflow() {
        assertEquals(2147483657L, value("2147483647 + qty"));
        assertEquals("2147483647 * 2147483647 * 2147483647 is out of range",
                fails("SELECT 2147483647 * 2147483647 * 2147483647 FROM items"));
    }

    @Test
    void arithmeticNeedsNumbersAndSaysSoBeforeReadingRows() {
        run("DELETE FROM items");
        assertEquals("* needs numbers, name is VARCHAR", fails("SELECT name * 2 FROM items"));
        assertEquals("+ needs numbers, city is VARCHAR", fails("SELECT id FROM items WHERE qty + city > 1"));
        assertEquals("- needs a number, name is VARCHAR", fails("SELECT -name FROM items"));
        assertEquals("+ needs numbers, 'a' is VARCHAR", fails("SELECT qty + 'a' FROM items"));
    }

    // Functions

    @Test
    void textFunctions() {
        assertEquals("PEN", value("UPPER(name)"));
        assertEquals("oslo", value("lower(city)"));
        assertEquals(3, value("LENGTH(name)"));
        assertEquals(3, value("CHAR_LENGTH(name)"));
        assertEquals("pen-1-Oslo", value("CONCAT(name, '-', id, '-', city)"));
        assertEquals("x", value("TRIM('  x  ')"));
        assertEquals("pin", value("REPLACE(name, 'e', 'i')"));
        assertEquals("42", value("UPPER(42)"));
    }

    @Test
    void substringFollowsMySql() {
        assertEquals("en", value("SUBSTRING(name, 2)"));
        assertEquals("e", value("SUBSTRING(name, 2, 1)"));
        assertEquals("en", value("SUBSTR(name, -2)"));
        assertEquals("", value("SUBSTRING(name, 0)"));
        assertEquals("", value("SUBSTRING(name, 9)"));
        assertEquals("", value("SUBSTRING(name, 1, 0)"));
    }

    @Test
    void numberFunctions() {
        assertEquals(10, value("ABS(-qty)"));
        assertEquals(1.5f, value("ABS(-price)"));
        assertEquals(2, value("ROUND(price)"));
        assertEquals(-2, value("ROUND(-1.5)"));
        assertEquals(1.5f, value("ROUND(price, 1)"));
        assertEquals(1200, value("ROUND(1234, -2)"));
        assertEquals(12.3f, select("SELECT ROUND(price, 1) AS v FROM items WHERE id = 3").get(0).get("v"));
        assertEquals(1, value("FLOOR(price)"));
        assertEquals(2, value("CEIL(price)"));
        assertEquals(2, value("CEILING(price)"));
        assertEquals(-2, value("FLOOR(-1.5)"));
    }

    @Test
    void emptyArgumentsGiveEmptyResultsExceptForNullFunctions() {
        Map<String, Object> box = select("SELECT UPPER(city) AS u, CONCAT(name, city) AS c, ROUND(price) AS r, "
                + "COALESCE(price, city, 0) AS co, IFNULL(city, 'none') AS i FROM items WHERE id = 4").get(0);
        assertEquals(Map.of("co", 0, "i", "none"), box);
        assertNull(value("NULLIF(qty, 10)"));
        assertEquals(10, value("NULLIF(qty, 3)"));
        assertEquals(1.5f, value("COALESCE(NULL, price, 0)"));
    }

    @Test
    void functionErrors() {
        assertEquals("UPPER takes 1 argument, got 2", parseFails("SELECT UPPER(name, city) FROM items"));
        assertEquals("SUBSTRING takes 2 or 3 arguments, got 1", parseFails("SELECT SUBSTRING(name) FROM items"));
        assertEquals("CONCAT takes at least 1 argument, got 0", parseFails("SELECT CONCAT() FROM items"));
        assertTrue(parseFails("SELECT NOPE(name) FROM items").startsWith("Unknown function NOPE, expected an aggregate"));
        assertEquals("ROUND needs a number, name is VARCHAR", fails("SELECT ROUND(name) FROM items"));
        assertEquals("SUBSTRING needs a number, 'x' is VARCHAR", fails("SELECT SUBSTRING(name, 'x') FROM items"));
        assertEquals("ROUND needs a whole number of decimals, 1.5 is FLOAT", fails("SELECT ROUND(price, 1.5) FROM items"));
    }

    @Test
    void functionNamesAreNotReserved() {
        run("CREATE TABLE t (upper INTEGER, length INTEGER)");
        run("INSERT INTO t VALUES (2, 3)");
        assertEquals(List.of(Map.of("upper", 2, "v", 6)), select("SELECT upper, upper * length AS v FROM t"));
    }

    // CASE

    @Test
    void searchedCase() {
        String sql = "SELECT CASE WHEN price > 10 THEN 'high' WHEN price > 2 THEN 'mid' ELSE 'low' END AS v FROM items";
        assertEquals(List.of("low", "mid", "high", "low"), select(sql).stream().map(r -> r.get("v")).toList());
    }

    @Test
    void simpleCaseAndMissingElse() {
        String sql = "SELECT CASE qty WHEN 10 THEN 'ten' WHEN 1 + 2 THEN 'three' END AS v FROM items";
        assertEquals(java.util.Arrays.asList("ten", "three", null, null), select(sql).stream().map(r -> r.get("v")).toList());
    }

    @Test
    void caseWorksInWhereOrderByAndAggregates() {
        assertEquals(List.of(1, 4), ids("CASE WHEN price IS NULL THEN 0 ELSE price END < 2"));
        assertEquals(List.of(3, 2, 1, 4), select("SELECT id FROM items ORDER BY CASE WHEN price IS NULL THEN 0 ELSE price END DESC")
                .stream().map(r -> r.get("id")).toList());
        assertEquals(List.of(Map.of("cheap", 2)), select("SELECT SUM(CASE WHEN price < 5 THEN 1 ELSE 0 END) AS cheap FROM items"));
    }

    @Test
    void caseErrors() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT CASE THEN 1 END FROM items"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT CASE WHEN qty > 1 THEN 1 FROM items"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT CASE WHEN qty > 1 1 END FROM items"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT CASE END FROM items"));
    }

    // WHERE

    @Test
    void expressionsOnBothSidesOfAComparison() {
        assertEquals(List.of(1, 3), ids("price * qty > 14"));
        assertEquals(List.of(1, 2), ids("UPPER(city) = 'OSLO'"));
        assertEquals(List.of(1), ids("qty > price"));
        assertEquals(List.of(3), ids("qty * 6 < price"));
        assertEquals(List.of(1, 4), ids("5 < qty"));
    }

    @Test
    void parenthesesCanStartAValueOrAGroupOfConditions() {
        assertEquals(List.of(3), ids("(price + 1) * 2 > 10"));
        assertEquals(List.of(1, 3), ids("(qty > 5 OR price > 10) AND id < 4"));
        assertEquals(List.of(1), ids("((qty + 1) * 2 > 20)"));
        assertEquals(List.of(1, 3), ids("((qty > 5) OR (price > (5 + 5))) AND (id) < (4)"));
        assertEquals("Expected ')', got end of input", parseFails("SELECT * FROM items WHERE (qty > 5"));
    }

    @Test
    void conditionsCanUseExpressionsInInBetweenAndLike() {
        assertEquals(List.of(1, 2), ids("qty * 2 IN (20, 3 + 3)"));
        assertEquals(List.of(1, 2, 4), ids("qty BETWEEN 1 + 2 AND 5 * 2"));
        assertEquals(List.of(1, 2), ids("UPPER(city) LIKE 'O%'"));
        assertEquals(List.of(4), ids("price * 2 IS NULL"));
    }

    @Test
    void constantsAreWorkedOutSoIndexesStillHelp() {
        assertEquals("[items: index PRIMARY (id=3)]", engine.explain(parse("SELECT * FROM items WHERE id = 1 + 2").getQuery()).toString());
        // A literal on the left is read the other way round
        assertEquals("[items: index PRIMARY (3 > id)]", engine.explain(parse("SELECT * FROM items WHERE 3 > id").getQuery()).toString());
        assertEquals(List.of(1, 2), ids("3 > id"));
        // Something computed from the column itself can't use the index
        assertEquals("[items: full scan]", engine.explain(parse("SELECT * FROM items WHERE id + 0 = 3").getQuery()).toString());
    }

    // Aggregates, GROUP BY, HAVING and ORDER BY

    @Test
    void aggregatesOfExpressionsAndExpressionsOfAggregates() {
        assertEquals(List.of(Map.of("revenue", 51.5f, "avg", 5.5f, "spread", 8)),
                select("SELECT SUM(price * qty) AS revenue, SUM(qty) / COUNT(*) AS avg, MAX(qty) - MIN(qty) AS spread FROM items"));
        assertEquals("SUM needs a number column, 'UPPER(name)' is VARCHAR", fails("SELECT SUM(UPPER(name)) FROM items"));
        assertEquals("SUM(...) can't be used in another aggregate", parseFails("SELECT COUNT(SUM(qty)) FROM items"));
    }

    @Test
    void groupByAnExpression() {
        assertEquals(List.of(Map.of("c", "OSLO", "n", 2), Map.of("c", "ROME", "n", 1), Map.of("n", 1)),
                select("SELECT UPPER(city) AS c, COUNT(*) AS n FROM items GROUP BY UPPER(city)"));
        // Through its alias, and built on further
        assertEquals(List.of(Map.of("c", "OSLO!"), Map.of("c", "ROME!"), Map.of()),
                select("SELECT CONCAT(UPPER(city), '!') AS c FROM items GROUP BY UPPER(city)"));
        assertEquals(List.of(Map.of("c", "OSLO", "n", 2)), select("SELECT UPPER(city) AS c, COUNT(*) AS n FROM items GROUP BY c HAVING n > 1"));
    }

    @Test
    void expressionsOfGroupColumnsAreAllowed() {
        assertEquals(List.of(Map.of("u", "OSLO"), Map.of("u", "OSLO"), Map.of("u", "ROME"), Map.of()),
                select("SELECT UPPER(city) AS u FROM items GROUP BY city"));
        assertEquals("Column 'qty' must be in GROUP BY, or inside an aggregate like COUNT or MAX",
                fails("SELECT qty + 1 FROM items GROUP BY city"));
        assertEquals("Column 'city' must be in GROUP BY, or inside an aggregate like COUNT or MAX",
                fails("SELECT UPPER(city) FROM items GROUP BY LOWER(city)"));
        assertEquals("Column 'price' must be in GROUP BY, or inside an aggregate like COUNT or MAX",
                fails("SELECT city FROM items GROUP BY city HAVING price > 1"));
    }

    @Test
    void havingAndOrderByWithExpressions() {
        assertEquals(List.of(Map.of("city", "Rome")), select("SELECT city FROM items GROUP BY city HAVING SUM(price * qty) / COUNT(*) > 20"));
        assertEquals(List.of(3, 1, 2, 4), select("SELECT id FROM items ORDER BY COALESCE(price * qty, 0) DESC").stream()
                .map(r -> r.get("id")).toList());
    }

    @Test
    void positionsInGroupByAndOrderBy() {
        assertEquals(List.of(Map.of("c", "ROME", "n", 1), Map.of("c", "OSLO", "n", 2), Map.of("n", 1)),
                select("SELECT UPPER(city) AS c, COUNT(*) AS n FROM items GROUP BY 1 ORDER BY 1 DESC"));
        assertEquals("ORDER BY 3 is not a position in the SELECT list, which has 2 values", parseFails("SELECT id, qty FROM items ORDER BY 3"));
        assertEquals("ORDER BY 1 needs the columns listed, not *", parseFails("SELECT * FROM items ORDER BY 1"));
        assertEquals("GROUP BY 0 is not a position in the SELECT list, which has 1 value", parseFails("SELECT city FROM items GROUP BY 0"));
        assertEquals("Can't GROUP BY an aggregate like COUNT(*)", parseFails("SELECT COUNT(*) FROM items GROUP BY 1"));
        // Only a whole-number literal is a position; other constants are ordinary values
        assertEquals(4, select("SELECT id FROM items ORDER BY 1 + 1").size());
    }

    @Test
    void distinctOnExpressions() {
        assertEquals(List.of(Map.of("u", "OSLO"), Map.of("u", "ROME"), Map.of()), select("SELECT DISTINCT UPPER(city) AS u FROM items"));
        assertEquals("ORDER BY qty must be in the SELECT list when using DISTINCT", fails("SELECT DISTINCT qty * 2 FROM items ORDER BY qty"));
        assertEquals(List.of(Map.of("qty * 2", 20), Map.of("qty * 2", 14), Map.of("qty * 2", 6), Map.of("qty * 2", 4)),
                select("SELECT DISTINCT qty * 2 FROM items ORDER BY qty * 2 DESC"));
    }

    // UPDATE ... SET and INSERT ... VALUES

    @Test
    void setUsesTheRowsOwnValues() {
        run("UPDATE items SET qty = qty + 1, price = price * 2 WHERE city = 'Oslo'");
        assertEquals(Map.of("qty", 11, "price", 3f), select("SELECT qty, price FROM items WHERE id = 1").get(0));
        run("UPDATE items SET name = CONCAT(UPPER(name), '!') WHERE id IN (2, 3)");
        assertEquals(List.of("pen", "CUP!", "BOOK!", "box"), select("SELECT name FROM items").stream().map(r -> r.get("name")).toList());
    }

    @Test
    void everyExpressionSeesTheOldRow() {
        run("CREATE TABLE p (a INTEGER, b INTEGER)");
        run("INSERT INTO p VALUES (1, 2)");
        run("UPDATE p SET a = b, b = a");
        assertEquals(List.of(Map.of("a", 2, "b", 1)), select("SELECT a, b FROM p"));
    }

    @Test
    void setCanMoveEveryPrimaryKey() {
        run("UPDATE items SET id = id + 1");
        assertEquals(List.of(2, 3, 4, 5), select("SELECT id FROM items").stream().map(r -> r.get("id")).toList());
        assertEquals("Duplicate primary key: id=0", fails("UPDATE items SET id = id % 2"));
        assertEquals("Cannot set primary key 'id' to the same value on 4 rows", fails("UPDATE items SET id = 7"));
        // 4 becomes 3, which row 3 keeps; 5 becoming 4 is fine, as 4 moves away
        assertEquals("Duplicate primary key: id=3", fails("UPDATE items SET id = id - 1 WHERE id > 3"));
        assertEquals(List.of(2, 3, 4, 5), select("SELECT id FROM items").stream().map(r -> r.get("id")).toList());
    }

    @Test
    void setErrors() {
        assertEquals("Column 'qty' expects INTEGER, got 5.0 (ROUND, FLOOR or CEIL give a whole number)", fails("UPDATE items SET qty = qty / 2"));
        assertEquals("Column 'qty' expects INTEGER, 4294967294 is out of range", fails("UPDATE items SET qty = 2147483647 * 2"));
        assertEquals("Unknown column 'big' (to set text, put it in quotes: 'big')", fails("UPDATE items SET name = big"));
        assertEquals("Unknown column 'nope'", fails("UPDATE items SET nope = 1"));
        assertEquals("SUM(...) can't be used in SET", parseFails("UPDATE items SET qty = SUM(qty)"));
        assertEquals("* needs numbers, name is VARCHAR", fails("UPDATE items SET qty = name * 2"));
        // Nothing changed
        assertEquals(10, select("SELECT qty FROM items WHERE id = 1").get(0).get("qty"));
        run("UPDATE items SET qty = FLOOR(qty / 2)");
        assertEquals(5, select("SELECT qty FROM items WHERE id = 1").get(0).get("qty"));
    }

    @Test
    void valuesCanBeConstantExpressions() {
        run("INSERT INTO items VALUES (2 + 3, UPPER('lamp'), 10 / 4, -2 * 3, CONCAT('O', 'slo'))");
        assertEquals(Map.of("id", 5, "name", "LAMP", "price", 2.5f, "qty", -6, "city", "Oslo"), select("SELECT * FROM items WHERE id = 5").get(0));
        assertEquals("VALUES can't use columns, got qty + 1", parseFails("INSERT INTO items (id, qty) VALUES (9, qty + 1)"));
        assertEquals("Expected a value, got 'qty' at position 40 (put text in quotes: 'qty')",
                parseFails("INSERT INTO items (id, qty) VALUES (9, qty)"));
        assertEquals("COUNT(...) can't be used in VALUES", parseFails("INSERT INTO items (id) VALUES (COUNT(*))"));
    }

    // Against the same arithmetic done in Java

    @Test
    void randomArithmeticMatchesJava() {
        run("CREATE TABLE n (a INTEGER, b INTEGER, c INTEGER)");
        Random random = new Random(13);
        String[] shapes = {"a + b * c", "(a + b) * c", "a - b - c", "a - (b - c)", "a * b % c", "-a + b * -c", "(a - b) % c"};
        Map<String, Object> expected = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            long a = random.nextInt(2001) - 1000, b = random.nextInt(2001) - 1000, c = random.nextInt(19) - 9;
            if (c == 0) c = 3;
            run("DELETE FROM n");
            run("INSERT INTO n VALUES (" + a + ", " + b + ", " + c + ")");
            long[] results = {a + b * c, (a + b) * c, a - b - c, a - (b - c), a * b % c, -a + b * -c, (a - b) % c};
            for (int s = 0; s < shapes.length; s++) {
                Object got = select("SELECT " + shapes[s] + " AS v FROM n").get(0).get("v");
                assertEquals((int) results[s], got, shapes[s] + " with a=" + a + ", b=" + b + ", c=" + c);
            }
            assertEquals((float) ((double) a / c), select("SELECT a / c AS v FROM n").get(0).get("v"));
        }
    }
}
