package db.storage;

import db.model.Journal;
import db.model.Table;
import db.parser.Parser;
import db.query.QueryEngine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    @TempDir
    Path dir;

    @BeforeEach
    void captureOut() {
        System.setOut(new PrintStream(output));
    }

    @AfterEach
    void restoreOut() {
        System.setOut(originalOut);
    }

    private static void run(QueryEngine engine, String... statements) {
        for (String sql : statements) Parser.parse(sql).execute(engine);
    }

    private static Map<String, String> state(QueryEngine engine) {
        Map<String, String> state = new LinkedHashMap<>();
        for (Table table : engine.getTables()) state.put(table.getName(), TableSerializer.toJSON(table, true));
        return state;
    }

    private static List<Object> ids(QueryEngine engine, String where) {
        return engine.select(((db.statement.SelectStatement) Parser.parse("SELECT id FROM users WHERE " + where)).getQuery())
                .stream().map(row -> row.get("id")).toList();
    }

    private Path log() {
        return dir.resolve(Database.LOG);
    }

    private static final String[] USERS = {
            "CREATE TABLE users (id INTEGER PRIMARY KEY AUTO_INCREMENT, name VARCHAR, age INTEGER)",
            "CREATE UNIQUE INDEX idx_name ON users (name)",
            "CREATE INDEX idx_age ON users (age)",
            "INSERT INTO users (name, age) VALUES ('Alice', 30), ('Bob', 25), ('Carol', 40)"};

    @Test
    void rollbackPutsEveryRowAndIndexBack() {
        QueryEngine engine = new QueryEngine();
        run(engine, USERS);
        Map<String, String> before = state(engine);

        run(engine, "BEGIN",
                "INSERT INTO users (name, age) VALUES ('Dan', 50)",
                "UPDATE users SET age = age + 1 WHERE age < 35",
                "UPDATE users SET id = 99 WHERE name = 'Carol'",
                "DELETE FROM users WHERE name = 'Alice'");
        // Inside the transaction its own changes show
        assertEquals(List.of(2, 99, 4), ids(engine, "age > 0"));
        run(engine, "ROLLBACK");

        assertEquals(before, state(engine));
        assertFalse(engine.inTransaction());
        // Indexes are rebuilt from the restored rows: no stale entries for Dan, and Carol is found by age again
        assertEquals(List.of(3), ids(engine, "age = 40"));
        assertEquals(List.of(), ids(engine, "age = 50"));
        run(engine, "INSERT INTO users (name, age) VALUES ('Dan', 50)");
        assertEquals(List.of(4), ids(engine, "name = 'Dan'"));
    }

    @Test
    void rollbackUndoesChangesToTheSameRowsNewestFirst() {
        QueryEngine engine = new QueryEngine();
        run(engine, USERS);
        Map<String, String> before = state(engine);
        // The same rows changed several times, and rows inserted and deleted within the transaction
        run(engine, "BEGIN",
                "UPDATE users SET age = 1 WHERE name = 'Bob'",
                "UPDATE users SET age = 2, name = 'Robert' WHERE id = 2",
                "INSERT INTO users (name, age) VALUES ('Dan', 50), ('Eve', 60)",
                "UPDATE users SET age = age + 100",
                "DELETE FROM users WHERE name = 'Dan' OR id = 2",
                "INSERT INTO users (name) VALUES ('Bob')",
                "DELETE FROM users WHERE age > 0",
                "ROLLBACK");
        assertEquals(before, state(engine));
        // Bob is back between Alice and Carol, where he was, not at the end
        assertEquals(List.of(1, 2, 3), ids(engine, "age > 0"));
        assertEquals(List.of(2), ids(engine, "name = 'Bob'"));
    }

    @Test
    void failedStatementLeavesTheTransactionOpen() throws IOException {
        try (Database database = Database.open(dir)) {
            QueryEngine engine = database.engine();
            run(engine, USERS);
            run(engine, "BEGIN", "INSERT INTO users (name) VALUES ('Dan')");
            // As in MySQL, only the statement fails, not the transaction
            assertThrows(IllegalArgumentException.class, () -> run(engine, "INSERT INTO users (name) VALUES ('Eve'), ('Alice')"));
            assertTrue(engine.inTransaction());
            run(engine, "COMMIT");
        }
        try (Database database = Database.open(dir)) {
            assertEquals(List.of(4), ids(database.engine(), "name = 'Dan'"));
            assertEquals(List.of(), ids(database.engine(), "name = 'Eve'"));
        }
    }

    @Test
    void commitWritesOneRecordForTheWholeTransaction() throws IOException {
        Map<String, String> committed;
        try (Database database = Database.open(dir)) {
            QueryEngine engine = database.engine();
            run(engine, USERS);
            long size = Files.size(log());
            run(engine, "BEGIN", "INSERT INTO users (name, age) VALUES ('Dan', 50)", "UPDATE users SET age = 0 WHERE name = 'Bob'",
                    "DELETE FROM users WHERE name = 'Carol'");
            assertEquals(size, Files.size(log()), "nothing is written before COMMIT");
            run(engine, "COMMIT");
            String[] lines = Files.readString(log()).split("\n");
            String last = lines[lines.length - 1];
            assertTrue(last.contains("\"op\": \"transaction\"") && last.contains("\"op\": \"insert\"")
                    && last.contains("\"op\": \"update\"") && last.contains("\"op\": \"delete\""), last);
            assertEquals(5, lines.length);
            committed = state(engine);
        }
        try (Database database = Database.open(dir)) {
            assertEquals(committed, state(database.engine()));
        }
    }

    @Test
    void crashBeforeCommitLosesTheWholeTransaction() throws IOException {
        Map<String, String> before;
        try (Database database = Database.open(dir)) {
            run(database.engine(), USERS);
            before = state(database.engine());
            run(database.engine(), "BEGIN", "INSERT INTO users (name) VALUES ('Dan')", "DELETE FROM users WHERE name = 'Alice'");
        }
        try (Database database = Database.open(dir)) {
            assertEquals(before, state(database.engine()));
        }
    }

    @Test
    void halfWrittenCommitCountsAsNoCommit() throws IOException {
        Map<String, String> before;
        try (Database database = Database.open(dir)) {
            run(database.engine(), USERS);
            before = state(database.engine());
            run(database.engine(), "BEGIN", "INSERT INTO users (name) VALUES ('Dan')", "UPDATE users SET age = 1", "COMMIT");
        }
        byte[] bytes = Files.readAllBytes(log());
        Files.write(log(), Arrays.copyOf(bytes, bytes.length - 40));
        try (Database database = Database.open(dir)) {
            assertEquals(before, state(database.engine()));
        }
    }

    @Test
    void schemaChangeCommitsTheOpenTransactionFirst() throws IOException {
        Map<String, String> after;
        try (Database database = Database.open(dir)) {
            QueryEngine engine = database.engine();
            run(engine, USERS);
            run(engine, "BEGIN", "INSERT INTO users (name) VALUES ('Dan')", "ALTER TABLE users ADD COLUMN city VARCHAR");
            assertFalse(engine.inTransaction());
            run(engine, "ROLLBACK");
            assertTrue(output.toString().contains("No transaction is open, nothing to roll back"));
            assertEquals(List.of(4), ids(engine, "name = 'Dan'"));

            for (String ddl : List.of("CREATE TABLE t (x INTEGER)", "CREATE INDEX idx_city ON users (city)", "DROP INDEX idx_city ON users",
                    "ALTER TABLE t RENAME TO t2", "DROP TABLE t2", "CHECKPOINT")) {
                run(engine, "BEGIN", "UPDATE users SET age = 7 WHERE name = 'Dan'", ddl);
                assertFalse(engine.inTransaction(), ddl);
            }
            // A schema change that fails its checks doesn't end the transaction
            run(engine, "BEGIN", "UPDATE users SET age = 8 WHERE name = 'Dan'");
            assertThrows(IllegalArgumentException.class, () -> run(engine, "ALTER TABLE users ADD COLUMN name VARCHAR"));
            assertTrue(engine.inTransaction());
            run(engine, "ROLLBACK");
            after = state(engine);
        }
        try (Database database = Database.open(dir)) {
            assertEquals(after, state(database.engine()));
            assertEquals(List.of(4), ids(database.engine(), "age = 7"));
        }
    }

    @Test
    void beginInsideATransactionCommitsIt() {
        QueryEngine engine = new QueryEngine();
        run(engine, USERS);
        run(engine, "BEGIN", "INSERT INTO users (name) VALUES ('Dan')", "START TRANSACTION", "INSERT INTO users (name) VALUES ('Eve')", "ROLLBACK");
        assertEquals(List.of(4), ids(engine, "name = 'Dan'"));
        assertEquals(List.of(), ids(engine, "name = 'Eve'"));
        assertTrue(output.toString().contains("Open transaction committed, new transaction started"));
    }

    @Test
    void automaticCheckpointNeverSavesUncommittedChanges() throws IOException {
        try (Database database = Database.open(dir, 600)) {
            QueryEngine engine = database.engine();
            run(engine, USERS);
            run(engine, "BEGIN");
            for (int i = 0; i < 30; i++) run(engine, "INSERT INTO users (name) VALUES ('uncommitted " + i + "')");
            // The log is past the limit, but nothing may be checkpointed while the transaction is open
            run(engine, "UPDATE users SET age = 1 WHERE name = 'Alice'");
            assertFalse(Files.exists(dir.resolve(Database.SNAPSHOT)));
        }
        try (Database database = Database.open(dir, 600)) {
            assertEquals(List.of(), ids(database.engine(), "name = 'uncommitted 0'"));
            QueryEngine engine = database.engine();
            run(engine, "BEGIN");
            for (int i = 0; i < 30; i++) run(engine, "INSERT INTO users (name) VALUES ('committed " + i + "')");
            run(engine, "COMMIT");
            // The commit made the log long, so the checkpoint ran right after it was written
            assertEquals(0, Files.size(log()));
            assertTrue(Files.readString(dir.resolve(Database.SNAPSHOT)).contains("committed 29"));
        }
    }

    @Test
    void failedCommitRollsBack() {
        QueryEngine engine = new QueryEngine();
        run(engine, USERS);
        Map<String, String> before = state(engine);
        engine.setJournal(new Journal() {
            @Override
            public void commit() {
                throw new StorageException("disk full");
            }
        });
        run(engine, "BEGIN", "INSERT INTO users (name) VALUES ('Dan')", "UPDATE users SET age = 1");
        StorageException e = assertThrows(StorageException.class, () -> run(engine, "COMMIT"));
        assertEquals("disk full", e.getMessage());
        assertFalse(engine.inTransaction());
        assertEquals(before, state(engine));
    }

    @Test
    void commitOrRollbackWithoutATransaction() {
        QueryEngine engine = new QueryEngine();
        run(engine, "COMMIT", "ROLLBACK");
        assertEquals("No transaction is open, nothing to commit\nNo transaction is open, nothing to roll back\n",
                output.toString().replace("\r\n", "\n"));
    }

    @Test
    void emptyTransactionWritesNothing() throws IOException {
        try (Database database = Database.open(dir)) {
            run(database.engine(), "CREATE TABLE t (x INTEGER)");
            long size = Files.size(log());
            run(database.engine(), "BEGIN", "SELECT * FROM t", "DELETE FROM t WHERE x = 1", "COMMIT");
            assertEquals(size, Files.size(log()));
        }
    }

    @Test
    void startNeedsTransaction() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Parser.parse("START users"));
        assertTrue(e.getMessage().startsWith("Expected TRANSACTION"), e.getMessage());
    }
}
