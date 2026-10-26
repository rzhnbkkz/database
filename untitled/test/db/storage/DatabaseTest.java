package db.storage;

import db.model.Change;
import db.model.Column;
import db.model.DataType;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// Closing a Database without a checkpoint leaves exactly what a crash would: the snapshot plus the log
class DatabaseTest {
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

    // Every table, with row ids, indexes and AUTO_INCREMENT counters, as a snapshot would hold it
    private static Map<String, String> state(QueryEngine engine) {
        Map<String, String> state = new LinkedHashMap<>();
        for (Table table : engine.getTables()) state.put(table.getName(), TableSerializer.toJSON(table, true));
        return state;
    }

    // Runs the statements, "crashes", reopens, and checks the reopened database is the same
    private Map<String, String> runAndRecover(String... statements) throws IOException {
        Map<String, String> before;
        try (Database database = Database.open(dir)) {
            run(database.engine(), statements);
            before = state(database.engine());
        }
        try (Database database = Database.open(dir)) {
            assertEquals(before, state(database.engine()));
        }
        return before;
    }

    private Path log() {
        return dir.resolve(Database.LOG);
    }

    @Test
    void recoversEveryKindOfChangeFromTheLog() throws IOException {
        Path exported = dir.resolveSibling(dir.getFileName() + "-pets.json");
        QueryEngine other = new QueryEngine();
        run(other, "CREATE TABLE pets (id INTEGER PRIMARY KEY, name VARCHAR)", "INSERT INTO pets VALUES (1, 'Rex'), (2, 'Tom')",
                "SAVE pets '" + exported + "'");

        Map<String, String> recovered = runAndRecover(
                "CREATE TABLE users (id INTEGER PRIMARY KEY AUTO_INCREMENT, name VARCHAR NOT NULL, score FLOAT, active BOOLEAN DEFAULT TRUE)",
                "INSERT INTO users (name, score) VALUES ('Alice', 1.1), ('Bob', NULL), ('Carol', 3), ('O''Brien \"quoted\"\\n', -0.5)",
                "UPDATE users SET score = score * 2, active = FALSE WHERE name = 'Alice'",
                "UPDATE users SET id = 10 WHERE name = 'Carol'",
                "DELETE FROM users WHERE name = 'Bob'",
                "INSERT INTO users (name) VALUES ('Dan')",
                "ALTER TABLE users ADD COLUMN level INTEGER NOT NULL DEFAULT 1",
                "ALTER TABLE users RENAME COLUMN level TO rank",
                "CREATE UNIQUE INDEX idx_name ON users (name)",
                "CREATE INDEX idx_rank_score ON users (rank, score)",
                "CREATE INDEX idx_rank ON users (rank)",
                "ALTER TABLE users DROP COLUMN score",
                "DROP INDEX idx_name ON users",
                "CREATE TABLE tmp (x INTEGER)",
                "INSERT INTO tmp VALUES (1)",
                "ALTER TABLE tmp RENAME TO scratch",
                "DROP TABLE scratch",
                "LOAD '" + exported + "'",
                "DELETE FROM pets WHERE id = 1");
        assertEquals(List.of("users", "pets"), List.copyOf(recovered.keySet()));

        // The recovered tables keep working: ids and AUTO_INCREMENT carry on where they were
        try (Database database = Database.open(dir)) {
            run(database.engine(), "INSERT INTO users (name) VALUES ('Eve')");
            assertEquals(12, database.engine().getTable("users").getRows().get(4).get("id"));
            // idx_rank_score went with the score column
            assertEquals(List.of("idx_rank"), database.engine().getTable("users").getIndexes().stream()
                    .map(index -> index.getName()).filter(name -> !name.equals(Table.PRIMARY)).toList());
        }
    }

    @Test
    void checkpointWritesASnapshotAndEmptiesTheLog() throws IOException {
        Map<String, String> before;
        try (Database database = Database.open(dir)) {
            run(database.engine(), "CREATE TABLE t (id INTEGER PRIMARY KEY, v VARCHAR)", "INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c')",
                    "DELETE FROM t WHERE id = 2", "CHECKPOINT");
            assertEquals(0, Files.size(log()));
            assertTrue(Files.exists(dir.resolve(Database.SNAPSHOT)));
            // Rows keep their ids through the snapshot, so these log records still name the right rows
            run(database.engine(), "UPDATE t SET v = 'z' WHERE id = 3", "INSERT INTO t VALUES (4, 'd')");
            before = state(database.engine());
        }
        try (Database database = Database.open(dir)) {
            assertEquals(before, state(database.engine()));
            assertEquals(2, database.recoveredChanges());
            assertEquals("z", database.engine().getTable("t").getRows().get(1).get("v"));
        }
    }

    @Test
    void crashAfterSnapshotBeforeEmptyingTheLogDoesNotRepeatChanges() throws IOException {
        Map<String, String> before;
        try (Database database = Database.open(dir)) {
            run(database.engine(), "CREATE TABLE t (id INTEGER PRIMARY KEY AUTO_INCREMENT, n INTEGER)",
                    "INSERT INTO t (n) VALUES (1), (2)", "UPDATE t SET n = n + 10");
            byte[] logBeforeCheckpoint = Files.readAllBytes(log());
            database.checkpoint();
            // As if the process stopped right after renaming the snapshot into place
            Files.write(log(), logBeforeCheckpoint);
            before = state(database.engine());
        }
        try (Database database = Database.open(dir)) {
            assertEquals(before, state(database.engine()));
            assertEquals(0, database.recoveredChanges());
            run(database.engine(), "INSERT INTO t (n) VALUES (3)");
            assertEquals(3, database.engine().getTable("t").getRows().get(2).get("id"));
        }
    }

    @Test
    void incompleteRecordAtTheEndIsDropped() throws IOException {
        Map<String, String> before;
        try (Database database = Database.open(dir)) {
            run(database.engine(), "CREATE TABLE t (id INTEGER)", "INSERT INTO t VALUES (1)");
            before = state(database.engine());
        }
        // A crash in the middle of writing the next record
        long goodSize = Files.size(log());
        Files.write(log(), "0badc0de {\"lsn\": 3, \"op\": \"insert\", \"tab".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

        try (Database database = Database.open(dir)) {
            assertEquals(before, state(database.engine()));
            assertEquals(goodSize, Files.size(log()));
            assertTrue(output.toString().contains("Dropped an incomplete record"));
            // New records go after the last good one, so they're found next time
            run(database.engine(), "INSERT INTO t VALUES (2)");
        }
        try (Database database = Database.open(dir)) {
            assertEquals(2, database.engine().getTable("t").getRows().size());
        }
    }

    @Test
    void recordWithoutItsNewlineIsIncomplete() throws IOException {
        try (Database database = Database.open(dir)) {
            run(database.engine(), "CREATE TABLE t (id INTEGER)", "INSERT INTO t VALUES (1)");
        }
        byte[] bytes = Files.readAllBytes(log());
        // The whole last record but its newline: it was never acknowledged, so it doesn't count
        Files.write(log(), java.util.Arrays.copyOf(bytes, bytes.length - 1));
        try (Database database = Database.open(dir)) {
            assertEquals(0, database.engine().getTable("t").getRows().size());
        }
    }

    @Test
    void damagedRecordFollowedByGoodOnesIsRefused() throws IOException {
        try (Database database = Database.open(dir)) {
            run(database.engine(), "CREATE TABLE t (id INTEGER)", "INSERT INTO t VALUES (1)", "INSERT INTO t VALUES (2)");
        }
        byte[] bytes = Files.readAllBytes(log());
        int firstNewline = new String(bytes, StandardCharsets.UTF_8).indexOf('\n');
        bytes[firstNewline + 20] ^= 1;
        Files.write(log(), bytes);

        StorageException e = assertThrows(StorageException.class, () -> Database.open(dir));
        assertTrue(e.getMessage().contains("damaged"), e.getMessage());
        // Nothing was cut off, and the folder isn't left locked
        assertArrayEquals(bytes, Files.readAllBytes(log()));
        Files.write(log(), new byte[0]);
        assertDoesNotThrow(() -> Database.open(dir).close());
    }

    @Test
    void failedStatementWritesNothing() throws IOException {
        try (Database database = Database.open(dir)) {
            run(database.engine(), "CREATE TABLE t (id INTEGER PRIMARY KEY)", "INSERT INTO t VALUES (1)");
            long size = Files.size(log());
            assertThrows(IllegalArgumentException.class, () -> run(database.engine(), "INSERT INTO t VALUES (2), (1)"));
            run(database.engine(), "UPDATE t SET id = 5 WHERE id = 99", "DELETE FROM t WHERE id = 99");
            assertEquals(size, Files.size(log()), "failed statements and ones that change no rows aren't logged");
        }
    }

    @Test
    void changeIsNotAppliedWhenTheLogCantBeWritten() {
        Table table = new Table("t", List.of(new Column("id", DataType.INTEGER, true), new Column("v", DataType.VARCHAR)));
        table.insertAll(List.of(Map.of("id", 1, "v", "a")));
        table.setJournal(new Journal() {
            @Override
            public void changed(Table t, Change change) {
                throw new StorageException("disk full");
            }
        });
        assertThrows(StorageException.class, () -> table.insertAll(List.of(Map.of("id", 2))));
        assertThrows(StorageException.class, () -> table.update(row -> true, Map.of("v", "b")));
        assertThrows(StorageException.class, () -> table.delete(row -> true));
        assertThrows(StorageException.class, () -> table.addColumn(new Column("x", DataType.INTEGER)));
        assertThrows(StorageException.class, () -> table.createIndex("idx_v", "v"));
        assertEquals(List.of(Map.of("id", 1, "v", "a")), table.getRows().stream().map(row -> row.getData()).toList());
        assertEquals(2, table.getColumns().size());
        assertEquals(1, table.getIndexes().size());
    }

    @Test
    void folderCanBeOpenedOnlyOnceAtATime() throws IOException {
        try (Database database = Database.open(dir)) {
            StorageException e = assertThrows(StorageException.class, () -> Database.open(dir));
            assertTrue(e.getMessage().contains("already open"), e.getMessage());
        }
        assertDoesNotThrow(() -> Database.open(dir).close());
    }

    @Test
    void halfWrittenSnapshotIsIgnored() throws IOException {
        try (Database database = Database.open(dir)) {
            run(database.engine(), "CREATE TABLE t (id INTEGER)", "INSERT INTO t VALUES (1)", "CHECKPOINT");
        }
        Files.writeString(dir.resolve(Database.SNAPSHOT + ".tmp"), "{\"lsn\": 9, \"tab");
        try (Database database = Database.open(dir)) {
            assertEquals(1, database.engine().getTable("t").getRows().size());
            assertFalse(Files.exists(dir.resolve(Database.SNAPSHOT + ".tmp")));
        }
    }

    @Test
    void lsnKeepsGrowingAcrossCheckpoints() throws IOException {
        try (Database database = Database.open(dir)) {
            run(database.engine(), "CREATE TABLE t (id INTEGER)", "INSERT INTO t VALUES (1)", "CHECKPOINT", "INSERT INTO t VALUES (2)");
        }
        String record = Files.readString(log());
        assertTrue(record.contains("\"lsn\": 3,"), record);
        assertTrue(Files.readString(dir.resolve(Database.SNAPSHOT)).startsWith("{\"lsn\": 2,"));
    }

    @Test
    void checkpointNeedsADataFolder() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> run(new QueryEngine(), "CHECKPOINT"));
        assertEquals("CHECKPOINT needs a data folder, this database is only in memory", e.getMessage());
    }
}
