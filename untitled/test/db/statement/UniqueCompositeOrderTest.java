package db.statement;

import db.model.Column;
import db.model.DataType;
import db.model.Index;
import db.model.Row;
import db.model.Table;
import db.parser.Parser;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class UniqueCompositeOrderTest {
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private QueryEngine engine;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(output));
        engine = new QueryEngine();
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email VARCHAR, city VARCHAR, age INTEGER)");
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total FLOAT)");
        run("INSERT INTO users VALUES (1, 'a@x', 'Oslo', 30)");
        run("INSERT INTO users VALUES (2, 'b@x', 'Rome', 25)");
        run("INSERT INTO users VALUES (3, 'c@x', 'Oslo', 41)");
        run("INSERT INTO users (id, email, city) VALUES (4, 'd@x', 'Oslo')");
        run("INSERT INTO users (id) VALUES (5)");
        run("INSERT INTO orders VALUES (10, 1, 9.5)");
        run("INSERT INTO orders VALUES (11, 3, 20)");
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

    private String fails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> run(sql)).getMessage();
    }

    private SelectStatement parse(String sql) {
        return (SelectStatement) Parser.parse(sql);
    }

    private List<String> explain(String sql) {
        return engine.explain(parse(sql).getQuery());
    }

    private List<Object> ids(String sql) {
        return engine.select(parse(sql).getQuery()).stream().map(r -> r.get("id")).toList();
    }

    private Table users() {
        return engine.getTable("users");
    }

    // Parsing

    @Test
    void parsesUniqueAndMultiColumnIndexes() {
        CreateIndexStatement s = (CreateIndexStatement) Parser.parse("create unique index idx ON users (city, age)");
        assertEquals(List.of("city", "age"), s.getColumns());
        assertTrue(s.isUnique());
        assertFalse(((CreateIndexStatement) Parser.parse("CREATE INDEX i ON users (city)")).isUnique());
        for (String bad : new String[]{"CREATE UNIQUE users (id INTEGER)", "CREATE UNIQUE TABLE t (a INTEGER)",
                "CREATE UNIQUE INDEX ON users (city)", "CREATE INDEX i ON users (city,)", "CREATE INDEX i ON users (city age)"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(bad), bad);
        }
    }

    @Test
    void uniqueIsNotReserved() {
        run("CREATE TABLE t (unique INTEGER)");
        run("CREATE UNIQUE INDEX unique ON t (unique)");
        assertEquals("[{name=unique, columns=unique, unique=true}]", printed("SHOW INDEX FROM t"));
    }

    @Test
    void createPrintsWhatWasMade() {
        assertEquals("Unique index 'idx_email' created on users (email)", printed("CREATE UNIQUE INDEX idx_email ON users (email)"));
        assertEquals("Index 'idx_city_age' created on users (city, age)", printed("CREATE INDEX idx_city_age ON users (city, age)"));
        assertEquals("[{name=PRIMARY, columns=id, unique=true}, {name=idx_email, columns=email, unique=true}, "
                + "{name=idx_city_age, columns=city, age, unique=false}]", printed("SHOW INDEX FROM users"));
    }

    @Test
    void createIndexChecksItsColumns() {
        assertEquals("A column is listed twice in index 'i'", fails("CREATE INDEX i ON users (city, city)"));
        assertEquals("Unknown column 'nope'", fails("CREATE INDEX i ON users (city, nope)"));
        run("CREATE INDEX idx_city_age ON users (city, age)");
        assertEquals("Index 'idx_city_age' already covers (city, age)", fails("CREATE INDEX other ON users (city, age)"));
        // Same columns in another order is a different index
        run("CREATE INDEX idx_age_city ON users (age, city)");
        run("CREATE INDEX idx_city ON users (city)");
        assertEquals(4, users().getIndexes().size());
    }

    // UNIQUE

    @Test
    void uniqueRejectsDuplicateInserts() {
        run("CREATE UNIQUE INDEX idx_email ON users (email)");
        assertEquals("Duplicate value for unique index 'idx_email': email=a@x", fails("INSERT INTO users VALUES (6, 'a@x', 'Rome', 1)"));
        assertEquals(5, users().getRows().size());
        assertNull(users().getRow(5), "the rejected row isn't stored");
    }

    @Test
    void uniqueAllowsManyEmptyCells() {
        run("CREATE UNIQUE INDEX idx_email ON users (email)");
        run("INSERT INTO users (id) VALUES (6)");
        run("INSERT INTO users (id) VALUES (7)");
        assertEquals(7, users().getRows().size());
    }

    @Test
    void uniqueRejectsDuplicateUpdates() {
        run("CREATE UNIQUE INDEX idx_email ON users (email)");
        assertEquals("Duplicate value for unique index 'idx_email': email=a@x", fails("UPDATE users SET email = 'a@x' WHERE id = 2"));
        // Two updated rows would share the new value, even though no existing row has it
        assertEquals("Duplicate value for unique index 'idx_email': email=z@x", fails("UPDATE users SET email = 'z@x' WHERE id > 2"));
        assertEquals("b@x", users().getRows().get(1).get("email"), "a failed update changes nothing");
        assertEquals(List.of(3), ids("SELECT id FROM users WHERE email = 'c@x'"));
    }

    @Test
    void uniqueAllowsUpdatesThatKeepValuesDistinct() {
        run("CREATE UNIQUE INDEX idx_email ON users (email)");
        run("UPDATE users SET email = 'a@x' WHERE id = 1");
        run("UPDATE users SET email = 'new@x' WHERE id = 1");
        run("UPDATE users SET age = 99 WHERE id > 0");
        assertEquals(List.of(1), ids("SELECT id FROM users WHERE email = 'new@x'"));
        assertEquals(List.of(), ids("SELECT id FROM users WHERE email = 'a@x'"));
    }

    @Test
    void multiColumnUniqueChecksTheWholeCombination() {
        run("CREATE UNIQUE INDEX idx_city_age ON users (city, age)");
        run("INSERT INTO users VALUES (6, 'e@x', 'Oslo', 25)");
        run("INSERT INTO users VALUES (7, 'f@x', 'Rome', 30)");
        assertEquals("Duplicate value for unique index 'idx_city_age': city=Oslo, age=30",
                fails("INSERT INTO users VALUES (8, 'g@x', 'Oslo', 30)"));
        // An empty age never conflicts, so a second (Oslo, no age) is fine
        run("INSERT INTO users (id, city) VALUES (9, 'Oslo')");
    }

    @Test
    void uniqueIndexOnExistingDuplicatesFails() {
        assertEquals("Cannot create unique index 'idx_city', two rows have city=Oslo", fails("CREATE UNIQUE INDEX idx_city ON users (city)"));
        assertTrue(users().getIndexes().stream().noneMatch(i -> i.getName().equals("idx_city")));
        run("CREATE UNIQUE INDEX idx_city_age ON users (city, age)");
    }

    @Test
    void primaryKeyKeepsItsOwnMessages() {
        run("CREATE UNIQUE INDEX idx_email ON users (email)");
        assertEquals("Duplicate primary key: id=1", fails("INSERT INTO users VALUES (1, 'q@x', 'Rome', 1)"));
        assertEquals("Cannot set primary key 'id' to the same value on 2 rows", fails("UPDATE users SET id = 9 WHERE id < 3"));
    }

    // Multi-column lookups

    @Test
    void leadingColumnsAreUsed() {
        run("CREATE INDEX idx_city_age ON users (city, age)");
        assertEquals(List.of("users: index idx_city_age (city=Oslo AND age>26)"), explain("SELECT * FROM users WHERE city = 'Oslo' AND age > 26"));
        // Written in any order, the conditions are used (and shown) in the index's column order
        assertEquals(List.of("users: index idx_city_age (city=Oslo AND age=30)"), explain("SELECT * FROM users WHERE age = 30 AND city = 'Oslo'"));
        assertEquals(List.of("users: index idx_city_age (city=Oslo)"), explain("SELECT * FROM users WHERE city = 'Oslo'"));
        assertEquals(List.of("users: index idx_city_age (city>N AND city<P)"), explain("SELECT * FROM users WHERE city > 'N' AND city < 'P'"));
    }

    @Test
    void laterColumnsAloneCannotUseTheIndex() {
        run("CREATE INDEX idx_city_age ON users (city, age)");
        assertEquals(List.of("users: full scan"), explain("SELECT * FROM users WHERE age = 30"));
        assertEquals(List.of("users: full scan"), explain("SELECT * FROM users WHERE age > 26"));
        // A range on the first column ends the prefix, so age isn't used too
        assertEquals(List.of("users: index idx_city_age (city>N)"), explain("SELECT * FROM users WHERE city > 'N' AND age = 30"));
    }

    @Test
    void multiColumnResultsIncludeRowsWithEmptyLaterCells() {
        run("CREATE INDEX idx_city_age ON users (city, age)");
        // Dan (4) lives in Oslo but has no age: city = 'Oslo' must still find him
        assertEquals(List.of(1, 3, 4), ids("SELECT id FROM users WHERE city = 'Oslo'"));
        assertEquals(List.of(1, 3), ids("SELECT id FROM users WHERE city = 'Oslo' AND age > 26"));
        assertEquals(List.of(1), ids("SELECT id FROM users WHERE city = 'Oslo' AND age < 35"));
        assertEquals(List.of(1), ids("SELECT id FROM users WHERE city = 'Oslo' AND age = 30"));
        assertEquals(List.of(), ids("SELECT id FROM users WHERE city = 'Paris' AND age = 30"));
    }

    @Test
    void moreMatchedColumnsWin() {
        run("CREATE INDEX idx_city ON users (city)");
        run("CREATE INDEX idx_city_age ON users (city, age)");
        assertEquals(List.of("users: index idx_city_age (city=Oslo AND age=30)"), explain("SELECT * FROM users WHERE city = 'Oslo' AND age = 30"));
        assertEquals(List.of("users: index idx_city (city=Oslo)"), explain("SELECT * FROM users WHERE city = 'Oslo'"));
        assertEquals(List.of("users: index idx_city_age (city=Oslo AND age>1)"), explain("SELECT * FROM users WHERE city = 'Oslo' AND age > 1"));
    }

    @Test
    void anExactUniqueMatchWinsOverEverything() {
        run("CREATE INDEX idx_city_age ON users (city, age)");
        run("CREATE UNIQUE INDEX idx_email ON users (email)");
        assertEquals(List.of("users: index idx_email (email=b@x)"),
                explain("SELECT * FROM users WHERE city = 'Rome' AND age = 25 AND email = 'b@x'"));
    }

    @Test
    void joinUsesAMultiColumnIndexOnTheJoinedTable() {
        run("CREATE INDEX idx_user_total ON orders (user_id, total)");
        assertEquals(List.of("users u: full scan", "orders o: index idx_user_total (o.user_id=u.id AND o.total>8) for each row so far"),
                explain("SELECT * FROM users u JOIN orders o ON o.user_id = u.id AND o.total > 8"));
        assertEquals(List.of(1, 3), engine.select(parse("SELECT u.id FROM users u JOIN orders o ON o.user_id = u.id AND o.total > 8")
                .getQuery()).stream().map(r -> r.get("u.id")).toList());
    }

    // ORDER BY read from an index

    @Test
    void orderByMatchingAnIndexSkipsTheSort() {
        assertEquals(List.of("users: index PRIMARY (all rows), in index order"), explain("SELECT * FROM users ORDER BY id"));
        assertEquals(List.of("users: index PRIMARY (all rows), in reverse index order"), explain("SELECT * FROM users ORDER BY id DESC"));
        run("CREATE INDEX idx_city_age ON users (city, age)");
        assertEquals(List.of("users: index idx_city_age (all rows), in index order"), explain("SELECT * FROM users ORDER BY city, age"));
        assertEquals(List.of("users: index idx_city_age (all rows), in reverse index order"),
                explain("SELECT * FROM users ORDER BY city DESC, age DESC"));
    }

    @Test
    void orderByThatNoIndexMatchesStillSorts() {
        run("CREATE INDEX idx_city_age ON users (city, age)");
        String[] sorted = {"city", "age", "age, city", "city, age DESC", "city, age, id", "COUNT(*)"};
        for (String order : sorted) {
            List<String> plan = explain("SELECT city, age FROM users " + (order.startsWith("COUNT") ? "GROUP BY city, age " : "")
                    + "ORDER BY " + order);
            assertTrue(plan.stream().anyMatch(line -> line.startsWith("sort by")), order);
        }
    }

    @Test
    void indexOrderGivesTheSameRowsAsSorting() {
        run("CREATE INDEX idx_city_age ON users (city, age)");
        // Empty cells first ascending, last descending: 5 has no city, 4 is Oslo without an age
        assertEquals(List.of(5, 4, 1, 3, 2), ids("SELECT id FROM users ORDER BY city, age"));
        assertEquals(List.of(2, 3, 1, 4, 5), ids("SELECT id FROM users ORDER BY city DESC, age DESC"));
        run("DROP INDEX idx_city_age ON users");
        assertEquals(List.of(5, 4, 1, 3, 2), ids("SELECT id FROM users ORDER BY city, age"));
        assertEquals(List.of(2, 3, 1, 4, 5), ids("SELECT id FROM users ORDER BY city DESC, age DESC"));
    }

    @Test
    void tiesKeepTableOrderInBothDirections() {
        run("CREATE INDEX idx_city ON users (city)");
        assertEquals(List.of(5, 1, 3, 4, 2), ids("SELECT id FROM users ORDER BY city"));
        assertEquals(List.of(2, 1, 3, 4, 5), ids("SELECT id FROM users ORDER BY city DESC"));
    }

    @Test
    void lookupOnTheSameIndexIsReadInOrder() {
        run("CREATE INDEX idx_city_age ON users (city, age)");
        assertEquals(List.of("users: index idx_city_age (city=Oslo), in index order"),
                explain("SELECT * FROM users WHERE city = 'Oslo' ORDER BY city, age"));
        assertEquals(List.of(4, 1, 3), ids("SELECT id FROM users WHERE city = 'Oslo' ORDER BY city, age"));
        assertEquals(List.of(3, 1, 4), ids("SELECT id FROM users WHERE city = 'Oslo' ORDER BY city DESC, age DESC"));
        assertEquals(List.of(1, 3), ids("SELECT id FROM users WHERE city = 'Oslo' AND age > 26 ORDER BY city, age"));
    }

    @Test
    void lookupOnAnotherIndexIsSorted() {
        run("CREATE INDEX idx_city ON users (city)");
        assertEquals(List.of("users: index idx_city (city=Oslo)", "sort by id DESC"),
                explain("SELECT * FROM users WHERE city = 'Oslo' ORDER BY id DESC"));
        assertEquals(List.of(4, 3, 1), ids("SELECT id FROM users WHERE city = 'Oslo' ORDER BY id DESC"));
    }

    @Test
    void orderedReadWorksThroughJoins() {
        assertEquals(List.of("orders o: index PRIMARY (all rows), in reverse index order", "users u: index PRIMARY (u.id=o.user_id) for each row so far"),
                explain("SELECT * FROM orders o JOIN users u ON u.id = o.user_id ORDER BY o.id DESC"));
        assertEquals(List.of(12, 11, 10), engine.select(parse("SELECT o.id FROM orders o JOIN users u ON u.id = o.user_id ORDER BY o.id DESC")
                .getQuery()).stream().map(r -> r.get("o.id")).toList());
        // Ordering by a joined table's column can't come from the first table's index
        assertTrue(explain("SELECT * FROM orders o JOIN users u ON u.id = o.user_id ORDER BY u.id").contains("sort by u.id"));
    }

    @Test
    void groupedQueriesAreSortedAfterGrouping() {
        assertTrue(explain("SELECT id, COUNT(*) FROM users GROUP BY id ORDER BY id").contains("sort by id"));
    }

    // Stopping early

    @Test
    void limitStopsReadingWhenNoSortIsNeeded() {
        assertEquals(List.of("users: full scan", "limit 2, stops reading early"), explain("SELECT * FROM users LIMIT 2"));
        assertEquals(List.of("users: index PRIMARY (all rows), in reverse index order", "limit 2 offset 1, stops reading early"),
                explain("SELECT * FROM users ORDER BY id DESC LIMIT 2 OFFSET 1"));
        assertEquals(List.of(4, 3), ids("SELECT id FROM users ORDER BY id DESC LIMIT 2 OFFSET 1"));
        assertEquals(List.of(1, 2), ids("SELECT id FROM users LIMIT 2"));
    }

    @Test
    void limitCannotStopEarlyWhenAllRowsAreNeeded() {
        assertEquals("limit 2", explain("SELECT * FROM users ORDER BY age LIMIT 2").get(2));
        assertEquals("limit 1", explain("SELECT COUNT(*) FROM users LIMIT 1").get(2));
        assertEquals(List.of(Map.of("COUNT(*)", 5)),
                engine.select(parse("SELECT COUNT(*) FROM users LIMIT 1").getQuery()).stream().map(Row::getData).toList());
    }

    @Test
    void stoppingEarlyCountsOnlyRowsThatMatch() {
        // Rows failing WHERE don't count towards the limit
        assertEquals(List.of(3, 4), ids("SELECT id FROM users WHERE id > 2 AND email != 'e@x' LIMIT 2"));
        assertEquals(List.of(3), ids("SELECT id FROM users WHERE age > 35 ORDER BY id LIMIT 5"));
    }

    @Test
    void stoppingEarlyKeepsEveryJoinedRowOfTheLastFirstTableRow() {
        run("INSERT INTO orders VALUES (13, 1, 1)");
        // Alice has two orders; LIMIT 2 must give both, then stop
        assertEquals(List.of(10, 13), engine.select(parse("SELECT o.id FROM users u JOIN orders o ON o.user_id = u.id ORDER BY u.id LIMIT 2")
                .getQuery()).stream().map(r -> r.get("o.id")).toList());
    }

    // SAVE and LOAD

    @Test
    void uniqueAndMultiColumnIndexesAreSavedAndLoaded() throws Exception {
        run("CREATE UNIQUE INDEX idx_email ON users (email)");
        run("CREATE INDEX idx_city_age ON users (city, age)");
        Path file = dir.resolve("users.json");
        run("SAVE users '" + file + "'");
        String json = Files.readString(file);
        assertTrue(json.contains("{\"name\": \"idx_email\", \"columns\": [\"email\"], \"unique\": true}"), json);
        assertTrue(json.contains("{\"name\": \"idx_city_age\", \"columns\": [\"city\", \"age\"]}"), json);

        engine = new QueryEngine();
        run("LOAD '" + file + "'");
        List<Index> indexes = users().getIndexes();
        assertEquals(List.of(List.of("id"), List.of("email"), List.of("city", "age")), indexes.stream().map(Index::getColumns).toList());
        assertEquals(List.of(true, true, false), indexes.stream().map(Index::isUnique).toList());
        assertThrows(IllegalArgumentException.class, () -> run("INSERT INTO users VALUES (9, 'a@x', 'Rome', 1)"));
    }

    @Test
    void filesWithTheOldOneColumnFormatStillLoad() throws Exception {
        Path file = dir.resolve("old.json");
        Files.writeString(file, "{\"name\": \"t\", \"columns\": [{\"name\": \"a\", \"type\": \"INTEGER\"}], "
                + "\"indexes\": [{\"name\": \"idx_a\", \"column\": \"a\"}], \"rows\": [{\"a\": 1}]}");
        run("LOAD '" + file + "'");
        assertEquals(List.of("a"), engine.getTable("t").getIndexOn("a").getColumns());
    }

    // Everything at once, against plain sorting and scanning

    @Test
    void sameResultsWithAndWithoutIndexesOnRandomData() {
        Random random = new Random(3);
        String[] ops = {"<", "<=", ">", ">=", "="};
        String[] orders = {"a, b", "a DESC, b DESC", "a", "a DESC", "b", "id DESC", "a, b DESC"};
        List<String> queries = new ArrayList<>();
        for (int k = 0; k < 60; k++) {
            String where = random.nextBoolean() ? "" : " WHERE a " + ops[random.nextInt(5)] + " " + random.nextInt(5)
                    + (random.nextBoolean() ? " AND b " + ops[random.nextInt(5)] + " '" + (char) ('p' + random.nextInt(4)) + "'" : "");
            String order = random.nextInt(4) == 0 ? "" : " ORDER BY " + orders[random.nextInt(orders.length)];
            String limit = random.nextBoolean() ? "" : " LIMIT " + random.nextInt(15) + (random.nextBoolean() ? " OFFSET " + random.nextInt(10) : "");
            queries.add("SELECT id, a, b FROM t" + where + order + limit);
            queries.add("SELECT x.id, y.c FROM t x JOIN u y ON y.a = x.a" + where.replace(" a ", " x.a ").replace(" b ", " x.b ")
                    + order.replace("a", "x.a").replace(" b", " x.b").replace("id", "x.id") + limit);
        }
        Map<String, List<Map<String, Object>>> expected = new HashMap<>();
        for (boolean indexed : new boolean[]{false, true}) {
            engine = new QueryEngine();
            engine.addTable(new Table("t", List.of(new Column("id", DataType.INTEGER), new Column("a", DataType.INTEGER),
                    new Column("b", DataType.VARCHAR))));
            engine.addTable(new Table("u", List.of(new Column("a", DataType.INTEGER), new Column("c", DataType.INTEGER))));
            if (indexed) {
                run("CREATE UNIQUE INDEX idx_id ON t (id)");
                run("CREATE INDEX idx_ab ON t (a, b)");
                run("CREATE INDEX idx_a ON t (a)");
                run("CREATE INDEX idx_b ON t (b)");
                run("CREATE INDEX idx_ua ON u (a)");
            }
            random.setSeed(11);
            for (int i = 0; i < 150; i++) {
                String a = random.nextInt(6) == 0 ? "NULL" : String.valueOf(random.nextInt(5));
                String b = random.nextInt(6) == 0 ? "NULL" : "'" + (char) ('p' + random.nextInt(4)) + "'";
                run("INSERT INTO t VALUES (" + i + ", " + a + ", " + b + ")");
            }
            for (int i = 0; i < 8; i++) run("INSERT INTO u VALUES (" + random.nextInt(5) + ", " + i + ")");
            run("DELETE FROM t WHERE a = 2 AND b < 'r'");
            run("UPDATE t SET b = 's' WHERE a >= 3 AND id < 60");
            for (String query : queries) {
                List<Map<String, Object>> got = engine.select(parse(query).getQuery()).stream().map(Row::getData).toList();
                if (!indexed) expected.put(query, got);
                else assertEquals(expected.get(query), got, query);
            }
        }
        assertTrue(explain("SELECT * FROM t ORDER BY a, b LIMIT 3").get(0).contains("idx_ab"), "indexes were used");
    }

    private String printed(String sql) {
        output.reset();
        run(sql);
        return output.toString().trim();
    }
}
