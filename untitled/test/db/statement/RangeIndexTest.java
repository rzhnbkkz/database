package db.statement;

import db.model.Column;
import db.model.DataType;
import db.model.Index;
import db.model.Row;
import db.model.Table;
import db.parser.Parser;
import db.query.ColumnRef;
import db.query.Condition;
import db.query.IndexLookup;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RangeIndexTest {
    private final PrintStream originalOut = System.out;
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        engine = new QueryEngine();
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR, age INTEGER, score FLOAT, active BOOLEAN)");
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total FLOAT)");
        run("INSERT INTO users VALUES (1, 'Carol', 41, 2.5, TRUE)");
        run("INSERT INTO users VALUES (2, 'Alice', 30, 7.5, FALSE)");
        run("INSERT INTO users VALUES (3, 'Bob', 25, 9, TRUE)");
        run("INSERT INTO users VALUES (4, 'Dan', 30, 7.5, FALSE)");
        run("INSERT INTO users (id, name) VALUES (5, 'Eve')");
        run("INSERT INTO orders VALUES (10, 1, 9.5)");
        run("INSERT INTO orders VALUES (11, 2, 20)");
        run("INSERT INTO orders VALUES (12, 3, 5)");
        run("CREATE INDEX idx_age ON users (age)");
        run("CREATE INDEX idx_name ON users (name)");
        run("CREATE INDEX idx_score ON users (score)");
        run("CREATE INDEX idx_total ON orders (total)");
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

    private List<String> explain(String sql) {
        return engine.explain(parse(sql).getQuery());
    }

    private List<Object> ids(String where) {
        return engine.select(parse("SELECT id FROM users WHERE " + where).getQuery()).stream().map(r -> r.get("id")).toList();
    }

    // Index.range on its own

    @Test
    void rangeReadsOffSortedValuesInTableOrder() {
        Index age = engine.getTable("users").getIndexOn("age");
        Table users = engine.getTable("users");
        // ids are row ids (0-based, in insertion order), so 0 = Carol, 1 = Alice, 2 = Bob, 3 = Dan
        assertEquals(List.of(0L, 1L, 3L), List.copyOf(age.range(30, true, null, false)));
        assertEquals(List.of(0L), List.copyOf(age.range(30, false, null, false)));
        assertEquals(List.of(1L, 2L, 3L), List.copyOf(age.range(null, false, 30, true)));
        assertEquals(List.of(2L), List.copyOf(age.range(null, false, 30, false)));
        assertEquals(List.of(1L, 2L, 3L), List.copyOf(age.range(25, true, 30, true)));
        assertEquals(List.of(), List.copyOf(age.range(25, false, 30, false)));
        assertEquals(List.of(0L, 1L, 2L, 3L), List.copyOf(age.range(null, false, null, false)), "both ends open: every value");
        assertEquals("Carol", users.getRows(age.range(31, true, 100, true)).get(0).get("name"));
    }

    @Test
    void emptyAndBackwardsRangesFindNothing() {
        Index age = engine.getTable("users").getIndexOn("age");
        assertTrue(age.range(50, true, 20, true).isEmpty());
        assertTrue(age.range(30, true, 30, false).isEmpty());
        assertTrue(age.range(100, false, null, false).isEmpty());
    }

    @Test
    void rangeComparesNumbersByValueAndRejectsOtherKinds() {
        Index age = engine.getTable("users").getIndexOn("age");
        assertEquals(3, age.range(29.5f, true, null, false).size());
        assertTrue(age.range("a", true, null, false).isEmpty(), "text against numbers matches nothing, like WHERE");
        assertTrue(age.range(true, true, null, false).isEmpty());
        Index name = engine.getTable("users").getIndexOn("name");
        assertTrue(name.range(5, true, null, false).isEmpty());
        assertEquals(2, name.range("B", true, "D", false).size(), "Bob and Carol");
    }

    // Which conditions use an index

    @Test
    void eachComparisonUsesTheIndex() {
        for (String op : new String[]{">", ">=", "<", "<="}) {
            assertEquals(List.of("users: index idx_age (age" + op + "30)"), explain("SELECT * FROM users WHERE age " + op + " 30"), op);
        }
    }

    @Test
    void twoEndsOnOneColumnBecomeOneRange() {
        assertEquals(List.of("users: index idx_age (age>=25 AND age<41)"),
                explain("SELECT * FROM users WHERE age >= 25 AND name != 'x' AND age < 41"));
        assertEquals(List.of("users: index idx_age (age<41 AND age>=25)"),
                explain("SELECT * FROM users WHERE (age < 41 AND (age >= 25))"));
    }

    @Test
    void equalityBeatsARange() {
        assertEquals(List.of("users: index idx_name (name=Bob)"), explain("SELECT * FROM users WHERE age > 20 AND name = 'Bob'"));
        assertEquals(List.of("users: index PRIMARY (id=3)"), explain("SELECT * FROM users WHERE age < 99 AND id = 3"));
    }

    @Test
    void aRangeWithBothEndsBeatsAnOpenOne() {
        assertEquals(List.of("users: index idx_score (score>1 AND score<8)"),
                explain("SELECT * FROM users WHERE age > 20 AND score > 1 AND score < 8"));
        assertEquals(List.of("users: index idx_age (age>20)"), explain("SELECT * FROM users WHERE age > 20 AND score > 1"));
    }

    @Test
    void conditionsAnIndexStillCannotAnswer() {
        String[] scans = {"age != 30", "age > 30 OR age < 26", "NOT age > 30", "age > id", "age > NULL"};
        for (String where : scans) {
            assertEquals(List.of("users: full scan"), explain("SELECT * FROM users WHERE " + where), where);
        }
    }

    @Test
    void joinCanUseARangeOnTheJoinedTable() {
        assertEquals(List.of("users u: full scan", "orders o: index idx_total (o.total>u.age) for each row so far"),
                explain("SELECT * FROM users u JOIN orders o ON o.total > u.age"));
        // Written the other way round, the condition is turned around
        assertEquals(List.of("users u: full scan", "orders o: index idx_total (u.score<o.total) for each row so far"),
                explain("SELECT * FROM users u JOIN orders o ON u.score < o.total"));
    }

    @Test
    void firstTableOfAJoinCanUseARangeFromWhere() {
        assertEquals(List.of("users u: index idx_age (u.age>=30)", "orders o: full scan for each row so far"),
                explain("SELECT * FROM users u JOIN orders o ON u.id = o.user_id WHERE u.age >= 30"));
    }

    // Results

    @Test
    void rangeResultsAreInTableOrder() {
        assertEquals(List.of(1, 2, 4), ids("age >= 30"));
        assertEquals(List.of(2, 3, 4), ids("age >= 25 AND age <= 30"));
        assertEquals(List.of(3), ids("age > 20 AND age < 30"));
        assertEquals(List.of(), ids("age > 41"));
        assertEquals(List.of(1, 2, 4), ids("score < 8"));
    }

    @Test
    void textAndBooleanRanges() {
        assertEquals(List.of(1, 3, 4), ids("name >= 'B' AND name < 'E'"));
        run("CREATE INDEX idx_active ON users (active)");
        assertEquals(List.of(1, 3), ids("active > FALSE"));
    }

    @Test
    void emptyCellsAreNeverInARange() {
        // Eve has no age
        assertFalse(ids("age < 1000").contains(5));
        assertFalse(ids("age > -1000").contains(5));
    }

    @Test
    void otherConditionsAreStillChecked() {
        assertEquals(List.of(4), ids("age >= 30 AND age < 41 AND name > 'B'"));
        assertEquals(List.of(2), ids("age > 25 AND age > 29 AND age < 35 AND score > 7 AND id < 3"));
    }

    @Test
    void rangeJoinFindsTheRightPairs() {
        // Orders over 9 are 10 (9.5) and 11 (20); every score with a value is below 9.5
        String sql = "SELECT u.name, o.id FROM users u JOIN orders o ON u.score < o.total AND o.total > 9";
        List<Map<String, Object>> expected = List.of(
                Map.of("u.name", "Carol", "o.id", 10), Map.of("u.name", "Carol", "o.id", 11),
                Map.of("u.name", "Alice", "o.id", 10), Map.of("u.name", "Alice", "o.id", 11),
                Map.of("u.name", "Bob", "o.id", 10), Map.of("u.name", "Bob", "o.id", 11),
                Map.of("u.name", "Dan", "o.id", 10), Map.of("u.name", "Dan", "o.id", 11));
        assertEquals(expected, engine.select(parse(sql).getQuery()).stream().map(Row::getData).toList());
        run("DROP INDEX idx_total ON orders");
        assertEquals(expected, engine.select(parse(sql).getQuery()).stream().map(Row::getData).toList(), "same without the index");
    }

    @Test
    void leftJoinWithAnEmptyBoundKeepsTheRow() {
        // Eve has no score, so nothing can be compared with it
        assertEquals(List.of(Map.of("u.name", "Eve")),
                engine.select(parse("SELECT u.name, o.id FROM users u LEFT JOIN orders o ON o.total > u.score WHERE u.id = 5")
                        .getQuery()).stream().map(Row::getData).toList());
    }

    @Test
    void emptyBoundLooksUpNothing() {
        // ON o.total > u.score, for a user without a score: results would still be right if every order were
        // tried (ON checks them), but the lookup shouldn't read the whole index for nothing
        IndexLookup lookup = IndexLookup.find(engine.getTable("orders"), "o", true,
                new Condition(new ColumnRef("o", "total"), Condition.Operator.GREATER, new ColumnRef("u", "score")), Set.of("u"));
        assertEquals("index idx_total (o.total>u.score)", lookup.toString());
        assertEquals(2, lookup.ids(new Row(Map.of("u.score", 7.5f))).size());
        assertTrue(lookup.ids(new Row(Map.of())).isEmpty());
    }

    @Test
    void updateAndDeleteThroughARange() {
        run("UPDATE users SET score = 0 WHERE age >= 30");
        assertEquals(List.of(1, 2, 4), ids("score = 0"));
        run("DELETE FROM users WHERE age > 26 AND age < 35");
        assertEquals(List.of(1, 3, 5), engine.select(parse("SELECT id FROM users").getQuery()).stream().map(r -> r.get("id")).toList());
        assertEquals(List.of(1), ids("age > 26"), "the index forgot the deleted rows");
    }

    @Test
    void indexesFollowUpdatesOfTheIndexedColumn() {
        run("UPDATE users SET age = 99 WHERE name = 'Bob'");
        assertEquals(List.of(3), ids("age > 50"));
        assertEquals(List.of(2, 4), ids("age < 40"));
    }

    @Test
    void sameResultsWithAndWithoutIndexesOnRandomData() {
        Random random = new Random(7);
        List<String> wheres = new ArrayList<>();
        String[] ops = {"<", "<=", ">", ">="};
        for (int k = 0; k < 40; k++) {
            String a = ops[random.nextInt(4)], b = ops[random.nextInt(4)];
            int x = random.nextInt(12) - 1, y = random.nextInt(12) - 1;
            wheres.add("n " + a + " " + x);
            wheres.add("n " + a + " " + x + " AND n " + b + " " + y);
            wheres.add("f " + a + " " + (x / 2.0) + " AND s " + b + " '" + (char) ('a' + random.nextInt(6)) + "'");
            wheres.add("n " + a + " " + x + " AND id " + b + " " + (y * 20));
        }
        Map<String, List<Object>> expected = new HashMap<>();
        for (boolean indexed : new boolean[]{false, true}) {
            engine = new QueryEngine();
            engine.addTable(new Table("t", List.of(new Column("id", DataType.INTEGER), new Column("n", DataType.INTEGER),
                    new Column("f", DataType.FLOAT), new Column("s", DataType.VARCHAR))));
            if (indexed) {
                run("CREATE INDEX idx_id ON t (id)");
                run("CREATE INDEX idx_n ON t (n)");
                run("CREATE INDEX idx_f ON t (f)");
                run("CREATE INDEX idx_s ON t (s)");
            }
            random.setSeed(99);
            for (int i = 0; i < 250; i++) {
                // Some cells left empty, so ranges must skip them
                String n = random.nextInt(8) == 0 ? "NULL" : String.valueOf(random.nextInt(10));
                run("INSERT INTO t VALUES (" + i + ", " + n + ", " + (random.nextInt(10) / 2.0) + ", '"
                        + (char) ('a' + random.nextInt(6)) + "')");
            }
            run("DELETE FROM t WHERE n >= 8 AND s < 'c'");
            run("UPDATE t SET n = 3 WHERE f > 3.5 AND f <= 4");
            for (String where : wheres) {
                List<Object> got = engine.select(parse("SELECT id FROM t WHERE " + where).getQuery()).stream().map(r -> r.get("id")).toList();
                if (!indexed) expected.put(where, got);
                else assertEquals(expected.get(where), got, where);
            }
        }
        assertTrue(explain("SELECT * FROM t WHERE n > 2 AND n < 5").get(0).contains("idx_n"), "indexes were used");
    }

    @Test
    void equalityStillWorksOnSortedIndexes() {
        assertEquals(List.of(2, 4), ids("age = 30"));
        assertEquals(List.of(2, 4), ids("age = 30.0"));
    }

    @Test
    void comparingANumberWithTextIsAnError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ids("age = '30'"));
        assertEquals("Can't compare INTEGER with VARCHAR: age = '30'", e.getMessage());
    }
}
