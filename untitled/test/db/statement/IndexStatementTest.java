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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class IndexStatementTest {
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private QueryEngine engine;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(output));
        engine = new QueryEngine();
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR)");
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER, total FLOAT)");
        run("INSERT INTO users VALUES (1, 'Alice')");
        run("INSERT INTO users VALUES (2, 'Bob')");
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

    private String print(String sql) {
        output.reset();
        run(sql);
        return output.toString().trim().replace("\r\n", "\n");
    }

    private List<String> explain(String select) {
        SelectStatement s = (SelectStatement) Parser.parse(select);
        return engine.explain(s.getQuery());
    }

    private List<Map<String, Object>> select(String sql) {
        SelectStatement s = (SelectStatement) Parser.parse(sql);
        return engine.select(s.getQuery()).stream().map(Row::getData).toList();
    }

    // Parsing

    @Test
    void parsesIndexCommands() {
        CreateIndexStatement create = assertInstanceOf(CreateIndexStatement.class,
                Parser.parse("create index idx_user on orders (user_id);"));
        assertEquals(List.of("idx_user", "orders"), List.of(create.getIndexName(), create.getTableName()));
        assertEquals(List.of("user_id"), create.getColumns());
        assertFalse(create.isUnique());
        DropIndexStatement drop = assertInstanceOf(DropIndexStatement.class, Parser.parse("DROP INDEX idx_user ON orders"));
        assertEquals(List.of("idx_user", "orders"), List.of(drop.getIndexName(), drop.getTableName()));
        assertEquals("orders", assertInstanceOf(ShowIndexStatement.class, Parser.parse("SHOW INDEX FROM orders")).getTableName());
        assertInstanceOf(ShowIndexStatement.class, Parser.parse("show indexes from orders"));
        assertInstanceOf(ExplainStatement.class, Parser.parse("EXPLAIN SELECT * FROM orders"));
    }

    @Test
    void rejectsMalformedIndexCommands() {
        String[] bad = {
                "CREATE INDEX ON orders (user_id)", "CREATE INDEX i orders (user_id)", "CREATE INDEX i ON orders",
                "CREATE INDEX i ON orders ()", "CREATE INDEX i ON orders (user_id", "CREATE INDEX i ON orders (a,)",
                "CREATE users (id INTEGER)", "DROP INDEX i", "DROP INDEX ON orders", "DROP orders",
                "SHOW INDEX orders", "SHOW TABLES", "EXPLAIN", "EXPLAIN DELETE FROM orders",
        };
        for (String sql : bad) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(sql), sql);
        }
    }

    @Test
    void indexWordsAreNotReserved() {
        run("CREATE TABLE t (`index` INTEGER, show INTEGER, explain INTEGER, drop INTEGER)");
        run("CREATE INDEX index ON t (show)");
        assertEquals("[{name=index, columns=show, unique=false}]", print("SHOW INDEX FROM t"));
    }

    // Running them

    @Test
    void createShowAndDrop() {
        assertEquals("Index 'idx_user' created on orders (user_id)", print("CREATE INDEX idx_user ON orders (user_id)"));
        assertEquals("[{name=PRIMARY, columns=id, unique=true}, {name=idx_user, columns=user_id, unique=false}]", print("SHOW INDEX FROM orders"));
        assertEquals("Index 'idx_user' dropped", print("DROP INDEX idx_user ON orders"));
        assertEquals("[{name=PRIMARY, columns=id, unique=true}]", print("SHOW INDEX FROM orders"));
    }

    @Test
    void commandsOnMissingTablesFail() {
        assertThrows(IllegalArgumentException.class, () -> run("CREATE INDEX i ON nope (a)"));
        assertThrows(IllegalArgumentException.class, () -> run("DROP INDEX i ON nope"));
        assertThrows(IllegalArgumentException.class, () -> run("SHOW INDEX FROM nope"));
        assertThrows(IllegalArgumentException.class, () -> run("EXPLAIN SELECT * FROM nope"));
    }

    // EXPLAIN: which index each query uses

    @Test
    void explainPrintsOneLinePerTable() {
        run("CREATE INDEX idx_user ON orders (user_id)");
        assertEquals("users u: index PRIMARY (u.id=1)\norders o: index idx_user (u.id=o.user_id) for each row so far",
                print("EXPLAIN SELECT * FROM users u JOIN orders o ON u.id = o.user_id WHERE u.id = 1"));
    }

    @Test
    void whereEqualsOnIndexedColumnUsesTheIndex() {
        assertEquals(List.of("orders: index PRIMARY (id=11)"), explain("SELECT * FROM orders WHERE id = 11"));
        assertEquals(List.of("orders: index PRIMARY (id=11)"), explain("SELECT * FROM orders WHERE total > 1 AND id = 11"));
        assertEquals(List.of("orders: index PRIMARY (id=11)"),
                explain("SELECT * FROM orders WHERE (total > 1 AND (id = 11 AND user_id = 1))"));
    }

    @Test
    void conditionsAnIndexCannotAnswerScan() {
        String[] scans = {
                "id != 11", "id = 11 OR id = 12", "NOT id = 11", "id = user_id", "user_id = 1", "id = NULL",
                "user_id > 1",
        };
        for (String where : scans) {
            assertEquals(List.of("orders: full scan"), explain("SELECT * FROM orders WHERE " + where), where);
        }
        assertEquals(List.of("orders: full scan"), explain("SELECT * FROM orders"));
    }

    @Test
    void joinUsesAnIndexOnTheJoinedTable() {
        assertEquals(List.of("users u: full scan", "orders o: full scan for each row so far"),
                explain("SELECT * FROM users u JOIN orders o ON u.id = o.user_id"));
        run("CREATE INDEX idx_user ON orders (user_id)");
        assertEquals(List.of("users u: full scan", "orders o: index idx_user (u.id=o.user_id) for each row so far"),
                explain("SELECT * FROM users u JOIN orders o ON u.id = o.user_id"));
        assertEquals(List.of("users u: full scan", "orders o: index idx_user (o.user_id=u.id) for each row so far"),
                explain("SELECT * FROM users u LEFT JOIN orders o ON o.user_id = u.id"));
        // Reversed, the joined table is users, and its primary key does the lookup
        assertEquals(List.of("orders o: full scan", "users u: index PRIMARY (u.id=o.user_id) for each row so far"),
                explain("SELECT * FROM orders o JOIN users u ON u.id = o.user_id"));
    }

    @Test
    void joinCanLookUpALiteralFromOn() {
        assertEquals(List.of("users u: full scan", "orders o: index PRIMARY (o.id=12) for each row so far"),
                explain("SELECT * FROM users u JOIN orders o ON o.id = 12 AND u.id = o.user_id"));
    }

    @Test
    void whereOnALaterTableIsNotUsedForTheFirst() {
        assertEquals(List.of("users u: full scan", "orders o: full scan for each row so far"),
                explain("SELECT * FROM users u JOIN orders o ON u.id = o.user_id WHERE o.id = 12"));
    }

    // Results are the same with or without an index

    @Test
    void indexedQueriesReturnTheRightRows() {
        run("CREATE INDEX idx_user ON orders (user_id)");
        assertEquals(List.of(Map.of("id", 10), Map.of("id", 11)), select("SELECT id FROM orders WHERE user_id = 1"));
        assertEquals(List.of(Map.of("id", 12)), select("SELECT id FROM orders WHERE user_id = 2.0"));
        assertEquals(List.of(), select("SELECT id FROM orders WHERE user_id = 1 AND total < 5"));
        assertEquals(List.of(
                Map.of("u.name", "Alice", "o.id", 10), Map.of("u.name", "Alice", "o.id", 11), Map.of("u.name", "Bob", "o.id", 12)
        ), select("SELECT u.name, o.id FROM users u JOIN orders o ON u.id = o.user_id"));
    }

    @Test
    void leftJoinThroughAnIndexKeepsRowsWithoutAMatch() {
        run("INSERT INTO users VALUES (3, 'Carol')");
        run("CREATE INDEX idx_user ON orders (user_id)");
        assertEquals(List.of(Map.of("name", "Alice"), Map.of("name", "Alice"), Map.of("name", "Bob"), Map.of("name", "Carol")),
                select("SELECT name FROM users u LEFT JOIN orders o ON u.id = o.user_id"));
    }

    @Test
    void updateAndDeleteThroughAnIndex() {
        run("CREATE INDEX idx_user ON orders (user_id)");
        assertEquals("2 rows updated", print("UPDATE orders SET user_id = 2 WHERE user_id = 1"));
        assertEquals(List.of(10, 11, 12), select("SELECT id FROM orders WHERE user_id = 2").stream().map(r -> r.get("id")).toList());
        assertEquals("1 row deleted", print("DELETE FROM orders WHERE user_id = 2 AND total > 10"));
        assertEquals(List.of(10, 12), select("SELECT id FROM orders WHERE user_id = 2").stream().map(r -> r.get("id")).toList());
        assertEquals("0 rows deleted", print("DELETE FROM orders WHERE id = 99"));
    }

    @Test
    void sameResultsWithAndWithoutIndexesOnRandomData() {
        Random random = new Random(42);
        String[] names = {"a", "b", "c", "d"};
        List<String> queries = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            queries.add("SELECT * FROM t WHERE n = " + k);
            queries.add("SELECT * FROM t WHERE s = '" + names[k % 4] + "' AND n > 1");
            queries.add("SELECT * FROM t WHERE f = " + (k / 2.0));
            queries.add("SELECT * FROM t x JOIN u y ON x.n = y.n WHERE y.s = '" + names[k % 4] + "'");
            queries.add("SELECT * FROM u y LEFT JOIN t x ON x.s = y.s AND x.n = " + k);
        }
        Map<String, List<Map<String, Object>>> expected = new java.util.HashMap<>();
        for (boolean indexed : new boolean[]{false, true}) {
            engine = new QueryEngine();
            run("CREATE TABLE t (id INTEGER PRIMARY KEY, n INTEGER, s VARCHAR, f FLOAT)");
            run("CREATE TABLE u (n INTEGER, s VARCHAR)");
            // Created before the data changes, so inserts, deletes and updates all go through the indexes
            if (indexed) {
                run("CREATE INDEX idx_n ON t (n)");
                run("CREATE INDEX idx_s ON t (s)");
                run("CREATE INDEX idx_f ON t (f)");
                run("CREATE INDEX idx_un ON u (n)");
                run("CREATE INDEX idx_us ON u (s)");
            }
            random.setSeed(42);
            for (int i = 0; i < 200; i++) {
                run("INSERT INTO t VALUES (" + i + ", " + random.nextInt(6) + ", '" + names[random.nextInt(4)] + "', "
                        + (random.nextInt(6) / 2.0) + ")");
            }
            for (int i = 0; i < 20; i++) {
                run("INSERT INTO u VALUES (" + random.nextInt(6) + ", '" + names[random.nextInt(4)] + "')");
            }
            run("DELETE FROM t WHERE n = 3 AND s = 'a'");
            run("UPDATE t SET n = 5 WHERE s = 'b' AND f = 1");
            for (String query : queries) {
                if (!indexed) expected.put(query, select(query));
                else assertEquals(expected.get(query), select(query), query);
            }
        }
        assertTrue(explain("SELECT * FROM t x JOIN u y ON x.n = y.n").get(1).contains("idx_un"), "indexes were used");
    }

    // SAVE and LOAD

    @Test
    void indexesAreSavedAndLoaded() {
        Path file = dir.resolve("orders.json");
        run("CREATE INDEX idx_user ON orders (user_id)");
        run("SAVE orders '" + file + "'");
        engine = new QueryEngine();
        run("LOAD '" + file + "'");
        Table orders = engine.getTable("orders");
        assertEquals(List.of("PRIMARY", "idx_user"), orders.getIndexes().stream().map(Index::getName).toList());
        assertEquals(List.of("orders: index idx_user (user_id=1)"), explain("SELECT * FROM orders WHERE user_id = 1"));
        assertEquals(2, select("SELECT * FROM orders WHERE user_id = 1").size());
    }

    @Test
    void tableWithOnlyPrimaryIndexSavesNoIndexList() throws Exception {
        Path file = dir.resolve("orders.json");
        run("SAVE orders '" + file + "'");
        assertFalse(java.nio.file.Files.readString(file).contains("indexes"));
    }

    @Test
    void secondaryIndexWorksWithoutPrimaryKey() {
        engine.addTable(new Table("logs", List.of(new Column("level", DataType.VARCHAR))));
        run("INSERT INTO logs VALUES ('info')");
        run("INSERT INTO logs VALUES ('error')");
        run("CREATE INDEX idx_level ON logs (level)");
        assertEquals(List.of(Map.of("level", "error")), select("SELECT * FROM logs WHERE level = 'error'"));
    }
}
