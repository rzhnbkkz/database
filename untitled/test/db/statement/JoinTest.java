package db.statement;

import db.model.Column;
import db.model.DataType;
import db.model.Row;
import db.model.Table;
import db.parser.Parser;
import db.query.Join;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JoinTest {
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        System.setOut(new PrintStream(output));
        engine = new QueryEngine();
        Table users = new Table("users", List.of(
                new Column("id", DataType.INTEGER, true),
                new Column("name", DataType.VARCHAR),
                new Column("manager_id", DataType.INTEGER)
        ));
        Table orders = new Table("orders", List.of(
                new Column("id", DataType.INTEGER, true),
                new Column("user_id", DataType.INTEGER),
                new Column("total", DataType.FLOAT)
        ));
        Table items = new Table("items", List.of(
                new Column("order_id", DataType.INTEGER),
                new Column("product", DataType.VARCHAR)
        ));
        engine.addTable(users);
        engine.addTable(orders);
        engine.addTable(items);
        engine.addTable(new Table("empty", List.of(new Column("user_id", DataType.INTEGER))));
        QueryEngine.insert(users, Map.of("id", 1, "name", "Alice"));
        QueryEngine.insert(users, Map.of("id", 2, "name", "Bob", "manager_id", 1));
        QueryEngine.insert(users, Map.of("id", 3, "name", "Carol", "manager_id", 1));
        QueryEngine.insert(orders, Map.of("id", 10, "user_id", 1, "total", 9.5f));
        QueryEngine.insert(orders, Map.of("id", 11, "user_id", 1, "total", 20f));
        QueryEngine.insert(orders, Map.of("id", 12, "user_id", 2, "total", 5f));
        QueryEngine.insert(items, Map.of("order_id", 10, "product", "pen"));
        QueryEngine.insert(items, Map.of("order_id", 12, "product", "cup"));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private List<Map<String, Object>> select(String sql) {
        SelectStatement s = (SelectStatement) Parser.parse(sql);
        return engine.select(s.getQuery()).stream().map(Row::getData).toList();
    }

    private IllegalArgumentException fails(String sql) {
        return assertThrows(IllegalArgumentException.class, () -> Parser.parse(sql).execute(engine));
    }

    // Parsing

    @Test
    void parsesJoinTypesAndAliases() {
        SelectStatement s = (SelectStatement) Parser.parse(
                "SELECT * FROM users u JOIN orders AS o ON u.id = o.user_id LEFT JOIN items ON items.order_id = o.id");
        assertEquals("users", s.getFrom().table());
        assertEquals("u", s.getFrom().alias());
        assertEquals(2, s.getJoins().size());
        Join first = s.getJoins().get(0), second = s.getJoins().get(1);
        assertEquals(Join.Type.INNER, first.type());
        assertEquals("o", first.table().alias());
        assertEquals("u.id=o.user_id", first.on().toString());
        assertEquals(Join.Type.LEFT, second.type());
        assertEquals("items", second.table().alias());
    }

    @Test
    void acceptsEveryJoinSpelling() {
        String[] spellings = {"JOIN", "INNER JOIN", "inner join", "LEFT JOIN", "LEFT OUTER JOIN", "left outer join"};
        for (String spelling : spellings) {
            SelectStatement s = (SelectStatement) Parser.parse("SELECT * FROM users " + spelling + " orders ON id = user_id");
            assertEquals(spelling.toUpperCase().startsWith("LEFT") ? Join.Type.LEFT : Join.Type.INNER,
                    s.getJoins().get(0).type(), spelling);
        }
    }

    @Test
    void rejectsMalformedJoins() {
        String[] bad = {
                "SELECT * FROM users JOIN orders", "SELECT * FROM users JOIN ON id = user_id",
                "SELECT * FROM users JOIN orders ON", "SELECT * FROM users INNER orders ON id = user_id",
                "SELECT * FROM users LEFT orders ON id = user_id", "SELECT * FROM users OUTER JOIN orders ON id = user_id",
                "SELECT * FROM users AS JOIN orders ON id = user_id", "SELECT u. FROM users u",
                "SELECT .id FROM users", "SELECT * FROM users u JOIN orders o ON u.id = o.",
        };
        for (String sql : bad) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(sql), sql);
        }
    }

    // Results

    @Test
    void innerJoinKeepsOnlyMatchingPairs() {
        assertEquals(List.of(
                Map.of("u.name", "Alice", "o.total", 9.5f),
                Map.of("u.name", "Alice", "o.total", 20f),
                Map.of("u.name", "Bob", "o.total", 5f)
        ), select("SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.user_id"));
    }

    @Test
    void starInAJoinLabelsEveryColumnWithItsTable() {
        Map<String, Object> first = select("SELECT * FROM users u JOIN orders o ON u.id = o.user_id").get(0);
        assertEquals(List.of("u.id", "u.name", "o.id", "o.user_id", "o.total"), List.copyOf(first.keySet()));
    }

    @Test
    void tableNameWorksWhenThereIsNoAlias() {
        assertEquals(List.of(Map.of("orders.id", 12)),
                select("SELECT orders.id FROM users JOIN orders ON users.id = orders.user_id WHERE users.name = 'Bob'"));
    }

    @Test
    void unqualifiedNamesWorkWhenOnlyOneTableHasThem() {
        assertEquals(List.of(Map.of("name", "Bob", "total", 5f)),
                select("SELECT name, total FROM users JOIN orders ON users.id = user_id WHERE total < 6"));
    }

    @Test
    void leftJoinKeepsRowsWithoutAMatch() {
        assertEquals(List.of(
                Map.of("name", "Alice", "o.id", 10),
                Map.of("name", "Alice", "o.id", 11),
                Map.of("name", "Bob", "o.id", 12),
                Map.of("name", "Carol")
        ), select("SELECT name, o.id FROM users u LEFT JOIN orders o ON u.id = o.user_id"));
    }

    @Test
    void joiningAnEmptyTable() {
        assertEquals(List.of(), select("SELECT * FROM users JOIN empty ON id = user_id"));
        assertEquals(3, select("SELECT * FROM users LEFT JOIN empty ON id = user_id").size());
        assertEquals(List.of(), select("SELECT * FROM empty JOIN users ON id = user_id"));
    }

    @Test
    void chainsSeveralJoins() {
        assertEquals(List.of(
                Map.of("u.name", "Alice", "product", "pen"),
                Map.of("u.name", "Alice"),
                Map.of("u.name", "Bob", "product", "cup")
        ), select("SELECT u.name, product FROM users u JOIN orders o ON u.id = o.user_id "
                + "LEFT JOIN items i ON i.order_id = o.id"));
    }

    @Test
    void innerJoinAfterLeftJoinDropsRowsWithoutAMatch() {
        assertEquals(List.of("pen", "cup"), select("SELECT product FROM users u LEFT JOIN orders o ON u.id = o.user_id "
                + "JOIN items i ON i.order_id = o.id").stream().map(r -> r.get("product")).toList());
    }

    @Test
    void selfJoinNeedsAliases() {
        assertEquals(List.of(
                Map.of("e.name", "Bob", "m.name", "Alice"),
                Map.of("e.name", "Carol", "m.name", "Alice")
        ), select("SELECT e.name, m.name FROM users e JOIN users m ON e.manager_id = m.id"));
        assertEquals("Table name 'users' is used twice, give one an alias",
                fails("SELECT * FROM users JOIN users ON id = id").getMessage());
    }

    @Test
    void tableStarSelectsOneTablesColumns() {
        assertEquals(List.of(Map.of("o.id", 12, "o.user_id", 2, "o.total", 5f)),
                select("SELECT o.* FROM users u JOIN orders o ON u.id = o.user_id WHERE u.name = 'Bob'"));
        Map<String, Object> row = select("SELECT u.name, o.* FROM users u JOIN orders o ON u.id = o.user_id "
                + "WHERE u.name = 'Bob'").get(0);
        assertEquals(List.of("u.name", "o.id", "o.user_id", "o.total"), List.copyOf(row.keySet()));
    }

    @Test
    void onCanUseAndOrNotAndLiterals() {
        assertEquals(List.of(11), select("SELECT o.id FROM users u JOIN orders o ON u.id = o.user_id AND o.total > 10")
                .stream().map(r -> r.get("o.id")).toList());
        assertEquals(List.of(10, 11, 12), select("SELECT o.id FROM users u JOIN orders o ON u.id = o.user_id OR o.id = 99")
                .stream().map(r -> r.get("o.id")).toList());
        // Every pair except the matching ones: 3 users x 3 orders - 3 matches
        assertEquals(6, select("SELECT * FROM users u JOIN orders o ON NOT (u.id = o.user_id)").size());
    }

    @Test
    void conditionsInOnAndWhereDifferForLeftJoin() {
        // In ON, the filter only decides what matches, so every user is still listed
        assertEquals(3, select("SELECT * FROM users u LEFT JOIN orders o ON u.id = o.user_id AND o.total > 10").size());
        // In WHERE, rows without an order have an empty total, which never matches
        assertEquals(1, select("SELECT * FROM users u LEFT JOIN orders o ON u.id = o.user_id WHERE o.total > 10").size());
    }

    @Test
    void printsJoinedRows() {
        Parser.parse("SELECT u.name, o.id FROM users u JOIN orders o ON u.id = o.user_id WHERE o.id = 12").execute(engine);
        assertEquals("""
                +--------+------+
                | u.name | o.id |
                +--------+------+
                | Bob    |   12 |
                +--------+------+
                1 row in set""", output.toString().trim());
    }

    @Test
    void joinDoesNotChangeTheTables() {
        select("SELECT * FROM users u LEFT JOIN orders o ON u.id = o.user_id");
        assertEquals(Map.of("id", 1, "name", "Alice"), engine.getTable("users").getRows().get(0).getData());
    }

    // Name errors

    @Test
    void ambiguousColumnIsAnError() {
        assertEquals("Column 'id' is ambiguous, write u.id or o.id",
                fails("SELECT id FROM users u JOIN orders o ON u.id = o.user_id").getMessage());
        assertEquals("Column 'id' is ambiguous, write u.id or o.id",
                fails("SELECT * FROM users u JOIN orders o ON id = user_id").getMessage());
    }

    @Test
    void unknownNamesAreErrors() {
        assertEquals("Unknown column 'o.nope'", fails("SELECT o.nope FROM users u JOIN orders o ON u.id = o.user_id").getMessage());
        assertEquals("Unknown column 'nope'", fails("SELECT * FROM users u JOIN orders o ON u.id = o.user_id WHERE nope = 1").getMessage());
        assertEquals("Unknown table 'x' in x.id", fails("SELECT x.id FROM users u JOIN orders o ON u.id = o.user_id").getMessage());
        assertEquals("Unknown table 'x' in x.*", fails("SELECT x.* FROM users u JOIN orders o ON u.id = o.user_id").getMessage());
        assertEquals("Table 'nope' does not exist", fails("SELECT * FROM users JOIN nope ON id = id").getMessage());
    }

    @Test
    void aliasReplacesTheTableName() {
        assertEquals("Unknown table 'users' in users.id",
                fails("SELECT * FROM users u JOIN orders o ON users.id = o.user_id").getMessage());
    }

    @Test
    void onCannotUseALaterTable() {
        assertEquals("Unknown table 'i' in i.order_id",
                fails("SELECT * FROM users u JOIN orders o ON o.id = i.order_id JOIN items i ON i.order_id = o.id").getMessage());
    }

    // Column names in single-table statements

    @Test
    void qualifiedNamesWorkWithOneTable() {
        assertEquals(List.of(Map.of("users.name", "Bob")), select("SELECT users.name FROM users WHERE users.id = 2"));
        // Without a join, u.* gives plain column names, the same as *
        assertEquals(List.of(Map.of("id", 2, "name", "Bob", "manager_id", 1)), select("SELECT u.* FROM users u WHERE u.id = 2"));
    }

    @Test
    void columnsCanBeComparedWithEachOther() {
        assertEquals(List.of(2, 3), select("SELECT id FROM users WHERE id > manager_id").stream().map(r -> r.get("id")).toList());
        assertEquals(List.of(11), select("SELECT id FROM orders WHERE id < total").stream().map(r -> r.get("id")).toList());
    }

    @Test
    void updateAndDeleteCheckColumnNames() {
        assertEquals("Unknown column 'nope'", fails("DELETE FROM users WHERE nope = 1").getMessage());
        assertEquals("Unknown column 'nope'", fails("UPDATE users SET name = 'X' WHERE nope = 1").getMessage());
        assertEquals(3, engine.getTable("users").getRows().size());
    }

    @Test
    void updateAndDeleteAcceptTheTableName() {
        Parser.parse("UPDATE users SET name = 'Bobby' WHERE users.id = 2").execute(engine);
        Parser.parse("DELETE FROM orders WHERE orders.total < 6").execute(engine);
        assertEquals("Bobby", engine.getTable("users").getRows().get(1).get("name"));
        assertEquals(2, engine.getTable("orders").getRows().size());
    }
}
