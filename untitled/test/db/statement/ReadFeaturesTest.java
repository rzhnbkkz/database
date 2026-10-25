package db.statement;

import db.model.Column;
import db.model.DataType;
import db.model.Row;
import db.model.Table;
import db.parser.Parser;
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

import static org.junit.jupiter.api.Assertions.*;

// IS NULL, IN, BETWEEN, LIKE, DISTINCT and AS
class ReadFeaturesTest {
    private final PrintStream originalOut = System.out;
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        engine = new QueryEngine();
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR, city VARCHAR, age INTEGER, score FLOAT)");
        run("INSERT INTO users VALUES (1, 'Alice', 'Oslo', 30, 7.5)");
        run("INSERT INTO users VALUES (2, 'Bob', 'Rome', 25, 9)");
        run("INSERT INTO users VALUES (3, 'Carol', 'Oslo', 41, 7.5)");
        run("INSERT INTO users (id, name, city) VALUES (4, 'Al', 'Oslo')");
        run("INSERT INTO users (id, name) VALUES (5, 'O''Brien')");
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

    private List<Object> ids(String where) {
        return select("SELECT id FROM users WHERE " + where).stream().map(r -> r.get("id")).toList();
    }

    private List<String> explain(String sql) {
        return engine.explain(parse(sql).getQuery());
    }

    private String fails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> run(sql)).getMessage();
    }

    private String parseFails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> Parser.parse(sql)).getMessage();
    }

    // IS NULL

    @Test
    void isNullFindsEmptyCells() {
        assertEquals(List.of(5), ids("city IS NULL"));
        assertEquals(List.of(4, 5), ids("age IS NULL"));
        assertEquals(List.of(1, 2, 3), ids("age IS NOT NULL"));
    }

    @Test
    void isNullIsNeverUnknown() {
        // Unlike age = NULL, which is unknown for every row
        assertEquals(List.of(), ids("age = NULL"));
        assertEquals(List.of(1, 2, 3), ids("NOT age IS NULL"));
        assertEquals(List.of(4, 5), ids("NOT (age IS NOT NULL)"));
    }

    @Test
    void isNullWorksInJoinsAndHaving() {
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, user_id INTEGER)");
        run("INSERT INTO orders VALUES (10, 1)");
        // Users without orders: the classic LEFT JOIN ... IS NULL
        assertEquals(List.of(2, 3, 4, 5), select("SELECT u.id FROM users u LEFT JOIN orders o ON o.user_id = u.id WHERE o.id IS NULL")
                .stream().map(r -> r.get("u.id")).toList());
        assertEquals(List.of(Map.of("city", "Oslo")), select("SELECT city FROM users GROUP BY city HAVING MIN(age) IS NOT NULL AND COUNT(*) > 1"));
    }

    @Test
    void parsesIsNull() {
        assertEquals("age IS NULL", parse("SELECT * FROM users WHERE age is null").getWhere().toString());
        assertEquals("age IS NOT NULL", parse("SELECT * FROM users WHERE age IS NOT NULL").getWhere().toString());
        for (String bad : new String[]{"age IS", "age IS NOT", "age IS 5", "age IS NOT TRUE", "age NOT NULL"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users WHERE " + bad), bad);
        }
    }

    // IN

    @Test
    void inMatchesAnyListedValue() {
        assertEquals(List.of(1, 3), ids("id IN (1, 3, 99)"));
        assertEquals(List.of(2), ids("city IN ('Rome', 'Paris')"));
        assertEquals(List.of(2), ids("id IN (2)"));
        assertEquals(List.of(1, 2), ids("score IN (7.5, 9) AND age < 35"));
    }

    @Test
    void notInFollowsSqlNullRules() {
        assertEquals(List.of(3, 4, 5), ids("id NOT IN (1, 2)"));
        // A NULL in the list makes NOT IN unknown for every row that isn't listed
        assertEquals(List.of(), ids("id NOT IN (1, NULL)"));
        assertEquals(List.of(1), ids("id IN (1, NULL)"));
        // An empty cell is never IN, and never NOT IN
        assertEquals(List.of(1, 3), ids("age NOT IN (25)"));
    }

    @Test
    void inIsWrittenOutAsOr() {
        assertEquals("id=1 OR id=2", parse("SELECT * FROM users WHERE id IN (1, 2)").getWhere().toString());
        assertEquals("NOT (id=1 OR id=2)", parse("SELECT * FROM users WHERE id NOT IN (1, 2)").getWhere().toString());
        assertEquals("id=1", parse("SELECT * FROM users WHERE id IN (1)").getWhere().toString());
        assertEquals("(id=1 OR id=2) AND age>1", parse("SELECT * FROM users WHERE id IN (1, 2) AND age > 1").getWhere().toString());
    }

    @Test
    void parsesIn() {
        assertEquals("Expected a value, got ')' at position 34", parseFails("SELECT * FROM users WHERE id IN ()"));
        for (String bad : new String[]{"id IN 1", "id IN (1", "id IN (1,)", "id IN (Bob)", "id NOT (1)", "id IN (1) (2)"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users WHERE " + bad), bad);
        }
    }

    // BETWEEN

    @Test
    void betweenIncludesBothEnds() {
        assertEquals(List.of(1, 2), ids("age BETWEEN 25 AND 30"));
        assertEquals(List.of(3), ids("age NOT BETWEEN 25 AND 30"));
        assertEquals(List.of(), ids("age BETWEEN 30 AND 25"));
        assertEquals(List.of(1, 3, 4), ids("name BETWEEN 'A' AND 'D' AND city = 'Oslo'"));
    }

    @Test
    void betweenCanUseColumnsAndAnIndex() {
        assertEquals(List.of(1, 2, 3), ids("age BETWEEN score AND 100"));
        run("CREATE INDEX idx_age ON users (age)");
        assertEquals(List.of("users: index idx_age (age>=25 AND age<=30)"), explain("SELECT * FROM users WHERE age BETWEEN 25 AND 30"));
        assertEquals(List.of(1, 2), ids("age BETWEEN 25 AND 30"));
    }

    @Test
    void parsesBetween() {
        assertEquals("age>=1 AND age<=2", parse("SELECT * FROM users WHERE age between 1 and 2").getWhere().toString());
        assertEquals("NOT (age>=1 AND age<=2)", parse("SELECT * FROM users WHERE age NOT BETWEEN 1 AND 2").getWhere().toString());
        // BETWEEN's AND belongs to it, the next AND joins conditions
        assertEquals("age>=1 AND age<=2 AND id=3", parse("SELECT * FROM users WHERE age BETWEEN 1 AND 2 AND id = 3").getWhere().toString());
        assertEquals(List.of(2), ids("age BETWEEN 20 AND 30 AND city = 'Rome'"));
        for (String bad : new String[]{"age BETWEEN 1", "age BETWEEN 1 OR 2", "age BETWEEN AND 2", "age BETWEEN 1 AND"}) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users WHERE " + bad), bad);
        }
    }

    // LIKE

    @Test
    void likeWildcards() {
        assertEquals(List.of(1, 4), ids("name LIKE 'Al%'"));
        assertEquals(List.of(2), ids("name LIKE '_ob'"));
        assertEquals(List.of(1, 3, 4), ids("name LIKE '%l%'"));
        assertEquals(List.of(4), ids("name LIKE 'Al'"));
        assertEquals(List.of(1, 2, 3, 4, 5), ids("name LIKE '%'"));
        assertEquals(List.of(), ids("name LIKE '_'"));
    }

    @Test
    void notLikeAndEmptyCells() {
        assertEquals(List.of(2, 5), ids("name NOT LIKE '%l%'"));
        // 5 has no city, so it's in neither
        assertEquals(List.of(1, 3, 4), ids("city LIKE 'O%'"));
        assertEquals(List.of(2), ids("city NOT LIKE 'O%'"));
    }

    @Test
    void likeIsCaseSensitiveAndEscapes() {
        assertEquals(List.of(), ids("name LIKE 'al%'"));
        run("INSERT INTO users (id, name) VALUES (6, '50% off')");
        run("INSERT INTO users (id, name) VALUES (7, '50 cents')");
        assertEquals(List.of(6), ids("name LIKE '50\\% %'"));
        assertEquals(List.of(6, 7), ids("name LIKE '50%'"));
        assertEquals(List.of(5), ids("name LIKE '%''%'"));
        // Regex characters are just text
        run("INSERT INTO users (id, name) VALUES (8, 'a.b*c')");
        assertEquals(List.of(8), ids("name LIKE 'a.b*%'"));
        assertEquals(List.of(), ids("name LIKE 'a_b\\_%'"));
    }

    @Test
    void likeOnNumbersMatchesTheirText() {
        assertEquals(List.of(1, 3), ids("age LIKE '%0' OR age LIKE '4_'"));
        assertEquals(List.of(1, 3), ids("score LIKE '7.%'"));
    }

    @Test
    void likeWithAFixedStartUsesAnIndex() {
        run("CREATE INDEX idx_name ON users (name)");
        assertEquals(List.of("users: index idx_name (name LIKE 'Al%')"), explain("SELECT * FROM users WHERE name LIKE 'Al%'"));
        assertEquals(List.of(1, 4), ids("name LIKE 'Al%'"));
        assertEquals(List.of(1), ids("name LIKE 'Al_ce'"));
        // No fixed start, or NOT LIKE: the index can't narrow it
        assertEquals(List.of("users: full scan"), explain("SELECT * FROM users WHERE name LIKE '%l'"));
        assertEquals(List.of("users: full scan"), explain("SELECT * FROM users WHERE name NOT LIKE 'Al%'"));
    }

    @Test
    void likeOnANumberColumnDoesNotUseItsIndex() {
        run("CREATE INDEX idx_age ON users (age)");
        assertEquals(List.of("users: full scan"), explain("SELECT * FROM users WHERE age LIKE '3%'"));
        assertEquals(List.of(1), ids("age LIKE '3%'"));
    }

    @Test
    void likeInHaving() {
        assertEquals(List.of(Map.of("city", "Oslo")), select("SELECT city FROM users GROUP BY city HAVING MAX(name) LIKE 'C%'"));
    }

    @Test
    void parsesLike() {
        assertEquals("name LIKE 'Al%'", parse("SELECT * FROM users WHERE name like 'Al%'").getWhere().toString());
        assertEquals("name NOT LIKE 'O''B%'", parse("SELECT * FROM users WHERE name NOT LIKE 'O''B%'").getWhere().toString());
        assertEquals("Expected a pattern in quotes, like 'Al%', got 'Bob' at position 37", parseFails("SELECT * FROM users WHERE name LIKE Bob"));
        assertEquals("Expected IN, BETWEEN or LIKE after NOT, got '=' at position 36", parseFails("SELECT * FROM users WHERE name NOT = 'Bob'"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT * FROM users WHERE name LIKE 5"));
    }

    // DISTINCT

    @Test
    void distinctRemovesDuplicateRows() {
        Map<String, Object> noCity = new HashMap<>();
        assertEquals(List.of(Map.of("city", "Oslo"), Map.of("city", "Rome"), noCity), select("SELECT DISTINCT city FROM users"));
        assertEquals(List.of(Map.of("city", "Oslo", "score", 7.5f), Map.of("city", "Rome", "score", 9f), Map.of("city", "Oslo"), noCity),
                select("SELECT DISTINCT city, score FROM users"));
        assertEquals(5, select("SELECT DISTINCT * FROM users").size());
    }

    @Test
    void distinctWithOrderByAndLimit() {
        assertEquals(List.of(Map.of("city", "Rome"), Map.of("city", "Oslo")), select("SELECT DISTINCT city FROM users ORDER BY city DESC LIMIT 2"));
        // LIMIT counts distinct rows, not the rows before duplicates were removed
        assertEquals(List.of(Map.of("city", "Oslo"), Map.of("city", "Rome")), select("SELECT DISTINCT city FROM users LIMIT 2"));
        assertEquals(List.of(Map.of("city", "Rome")), select("SELECT DISTINCT city FROM users LIMIT 1 OFFSET 1"));
        // The first two matching rows are both Oslo, so stopping after two rows would lose the second value
        assertEquals(List.of(Map.of("city", "Oslo"), Map.of()), select("SELECT DISTINCT city FROM users WHERE id != 2 LIMIT 2"));
    }

    @Test
    void distinctOrderByMustBeSelected() {
        assertEquals("ORDER BY age must be in the SELECT list when using DISTINCT", fails("SELECT DISTINCT city FROM users ORDER BY age"));
        // Selected under an alias, or with *, is fine
        assertEquals(3, select("SELECT DISTINCT city AS c FROM users ORDER BY c").size());
        assertEquals(5, select("SELECT DISTINCT * FROM users ORDER BY age").size());
    }

    @Test
    void distinctOnGroupedRows() {
        assertEquals(List.of(Map.of("COUNT(*)", 3), Map.of("COUNT(*)", 1)), select("SELECT DISTINCT COUNT(*) FROM users GROUP BY city"));
    }

    @Test
    void explainShowsDistinctAndNoEarlyStop() {
        assertEquals(List.of("users: full scan", "remove duplicate rows", "limit 1"), explain("SELECT DISTINCT city FROM users LIMIT 1"));
    }

    @Test
    void aggregatesWithDistinct() {
        assertEquals(List.of(Map.of("COUNT(DISTINCT city)", 2, "COUNT(city)", 4, "SUM(DISTINCT score)", 16.5f, "AVG(DISTINCT score)", 8.25f)),
                select("SELECT COUNT(DISTINCT city), COUNT(city), SUM(DISTINCT score), AVG(DISTINCT score) FROM users"));
        assertEquals(List.of(Map.of("city", "Oslo", "COUNT(DISTINCT score)", 1), Map.of("city", "Rome", "COUNT(DISTINCT score)", 1)),
                select("SELECT city, COUNT(DISTINCT score) FROM users WHERE city IS NOT NULL GROUP BY city"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT COUNT(DISTINCT *) FROM users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT COUNT(DISTINCT) FROM users"));
    }

    // AS

    @Test
    void aliasesLabelColumns() {
        assertEquals(List.of(Map.of("who", "Bob", "total", 1)),
                select("SELECT name AS who, COUNT(*) AS total FROM users WHERE id = 2 GROUP BY name"));
        // AS is optional
        assertEquals(List.of(Map.of("who", "Bob")), select("SELECT name who FROM users WHERE id = 2"));
    }

    @Test
    void aliasesWorkInGroupByHavingAndOrderBy() {
        assertEquals(List.of(Map.of("place", "Oslo", "n", 3)),
                select("SELECT city AS place, COUNT(*) AS n FROM users GROUP BY place HAVING n > 1 ORDER BY n DESC"));
        assertEquals(List.of(5, 4, 3, 2, 1), select("SELECT id AS k FROM users ORDER BY k DESC").stream().map(r -> r.get("k")).toList());
    }

    @Test
    void aliasWinsOverAColumnWithTheSameName() {
        // ORDER BY age means the alias, which is score: 4 and 5 have none, then 7.5, 7.5, 9.
        // Sorting by the real age column would give 4, 5, 2, 1, 3.
        assertEquals(List.of(4, 5, 1, 3, 2), select("SELECT id, score AS age FROM users ORDER BY age, id").stream()
                .map(r -> r.get("id")).toList());
    }

    @Test
    void aliasesAreNotAllowedInWhere() {
        assertEquals("Unknown column 'n'", fails("SELECT name AS n FROM users WHERE n = 'Bob'"));
    }

    @Test
    void aliasErrors() {
        assertEquals("Alias 'a' is used twice", parseFails("SELECT name AS a, city AS a FROM users"));
        assertEquals("u.* can't have an alias", parseFails("SELECT u.* AS x FROM users u"));
        assertEquals("Can't GROUP BY an aggregate like COUNT(*)", parseFails("SELECT COUNT(*) AS n FROM users GROUP BY n"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT name AS FROM users"));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("SELECT name AS 'x' FROM users"));
        // Strict GROUP BY still applies under an alias
        assertEquals("Column 'name' must be in GROUP BY, or inside an aggregate like COUNT or MAX",
                fails("SELECT name AS n, COUNT(*) FROM users GROUP BY city"));
    }

    @Test
    void newKeywordsNeedBackticksAsNames() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("CREATE TABLE t (in INTEGER)"));
        run("CREATE TABLE t (`in` INTEGER, `like` VARCHAR)");
        run("INSERT INTO t VALUES (1, 'x')");
        assertEquals(List.of(Map.of("in", 1)), select("SELECT `in` FROM t WHERE `like` LIKE 'x'"));
    }

    // Everything against the same queries without indexes

    @Test
    void sameResultsWithAndWithoutIndexesOnRandomData() {
        Random random = new Random(5);
        List<String> queries = new ArrayList<>();
        for (int k = 0; k < 50; k++) {
            int x = random.nextInt(8), y = random.nextInt(8);
            String s = String.valueOf((char) ('a' + random.nextInt(4)));
            queries.add("SELECT id FROM t WHERE n BETWEEN " + x + " AND " + y);
            queries.add("SELECT id FROM t WHERE n NOT BETWEEN " + x + " AND " + y + " AND w LIKE '" + s + "%'");
            queries.add("SELECT id FROM t WHERE w LIKE '" + s + "_%' OR n IN (" + x + ", " + y + ")");
            queries.add("SELECT id FROM t WHERE w IS NULL OR n NOT IN (" + x + ", " + y + ")");
            queries.add("SELECT DISTINCT n, w FROM t WHERE w LIKE '%" + s + "' ORDER BY n DESC, w LIMIT " + random.nextInt(6));
            queries.add("SELECT w AS k, COUNT(DISTINCT n) AS c FROM t WHERE n IS NOT NULL GROUP BY k HAVING c > 1 ORDER BY c, k");
        }
        Map<String, List<Map<String, Object>>> expected = new HashMap<>();
        for (boolean indexed : new boolean[]{false, true}) {
            engine = new QueryEngine();
            engine.addTable(new Table("t", List.of(new Column("id", DataType.INTEGER), new Column("n", DataType.INTEGER),
                    new Column("w", DataType.VARCHAR))));
            if (indexed) {
                run("CREATE INDEX idx_n ON t (n)");
                run("CREATE INDEX idx_w ON t (w)");
                run("CREATE INDEX idx_nw ON t (n, w)");
            }
            random.setSeed(8);
            for (int i = 0; i < 120; i++) {
                String n = random.nextInt(7) == 0 ? "NULL" : String.valueOf(random.nextInt(8));
                String w = random.nextInt(7) == 0 ? "NULL" : "'" + (char) ('a' + random.nextInt(4)) + (char) ('a' + random.nextInt(4)) + "'";
                run("INSERT INTO t VALUES (" + i + ", " + n + ", " + w + ")");
            }
            for (String query : queries) {
                List<Map<String, Object>> got = select(query);
                if (!indexed) expected.put(query, got);
                else assertEquals(expected.get(query), got, query);
            }
        }
        assertTrue(explain("SELECT * FROM t WHERE w LIKE 'b%'").get(0).contains("idx_w"), "indexes were used");
    }
}
