package db.storage;

import db.model.Change;
import db.model.Column;
import db.model.Journal;
import db.model.Row;
import db.model.Table;
import db.query.QueryEngine;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// A database kept in a data folder: snapshot.json holds every table as of a log sequence number (LSN), and
// wal.log every change since, each written and forced to disk before it's applied. Opening the folder loads
// the snapshot and replays the log records after its LSN. A checkpoint writes a new snapshot and empties the
// log; since the snapshot names its LSN, a crash between the two can't make a change happen twice.
public class Database implements Journal, Closeable {
    static final String SNAPSHOT = "snapshot.json";
    static final String LOG = "wal.log";
    static final String LOCK = "lock";
    // The log size at which the next write first runs a checkpoint, so the log (and replaying it) stays short
    public static final long DEFAULT_CHECKPOINT_SIZE = 16 * 1024 * 1024;

    private final Path dir;
    private final QueryEngine engine = new QueryEngine();
    private final FileChannel lockChannel;
    private final WriteAheadLog log;
    // The LSN of the last change written, in the log or the snapshot
    private long lastLsn = 0;
    private int recovered = 0;
    private final long checkpointSize;
    // The log size that triggers the next automatic checkpoint; moved on when one fails, so it's not retried every write
    private long nextCheckpointAt;

    private Database(Path dir, FileChannel lockChannel, WriteAheadLog log, long checkpointSize) {
        this.dir = dir;
        this.lockChannel = lockChannel;
        this.log = log;
        this.checkpointSize = checkpointSize;
        this.nextCheckpointAt = checkpointSize;
    }

    // Opens the folder, creating it if needed, and recovers every change that was acknowledged
    public static Database open(Path dir) throws IOException {
        return open(dir, DEFAULT_CHECKPOINT_SIZE);
    }

    // checkpointSize: the log size in bytes at which a checkpoint runs by itself
    public static Database open(Path dir, long checkpointSize) throws IOException {
        if (checkpointSize <= 0) throw new IllegalArgumentException("The checkpoint size must be above 0");
        Files.createDirectories(dir);
        FileChannel lockChannel = FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = lockChannel.tryLock();
            if (lock == null) throw new OverlappingFileLockException();
        } catch (OverlappingFileLockException e) {
            lockChannel.close();
            throw new StorageException("Data folder " + dir + " is already open in another process");
        }
        WriteAheadLog log = null;
        try {
            log = new WriteAheadLog(dir.resolve(LOG));
            Database database = new Database(dir, lockChannel, log, checkpointSize);
            database.recover();
            // A long log left by a crash: a checkpoint now makes the next start fast again
            database.checkpointIfLarge();
            return database;
        } catch (IOException | RuntimeException e) {
            if (log != null) log.close();
            lockChannel.close();
            throw e;
        }
    }

    public QueryEngine engine() {
        return engine;
    }

    // How many log records opening the folder replayed
    public int recoveredChanges() {
        return recovered;
    }

    private void recover() throws IOException {
        // A snapshot that was being written when the process stopped; the previous one is still whole
        Files.deleteIfExists(dir.resolve(SNAPSHOT + ".tmp"));
        Path snapshot = dir.resolve(SNAPSHOT);
        if (Files.exists(snapshot)) {
            Map<String, Object> root = TableDeserializer.asObject(
                    new TableDeserializer.JsonReader(Files.readString(snapshot)).readDocument(), "snapshot");
            lastLsn = TableDeserializer.asId(TableDeserializer.require(root, "lsn"));
            for (Object table : TableDeserializer.asArray(TableDeserializer.require(root, "tables"), "tables")) {
                engine.addTable(TableDeserializer.fromJSON(table));
            }
        }
        for (WriteAheadLog.Record record : log.readAll()) {
            // Already in the snapshot: the process stopped after writing it but before emptying the log
            if (record.lsn() <= lastLsn) continue;
            try {
                replay(record.fields());
            } catch (IllegalArgumentException e) {
                throw new StorageException("Log record " + record.lsn() + " in " + dir.resolve(LOG) + " can't be applied: " + e.getMessage());
            }
            lastLsn = record.lsn();
            recovered++;
        }
        // Only now, so the replay above isn't written to the log again
        engine.setJournal(this);
    }

    private void replay(Map<String, Object> fields) {
        String op = TableDeserializer.asString(TableDeserializer.require(fields, "op"), "op");
        String name = TableDeserializer.asString(TableDeserializer.require(fields, "table"), "table");
        switch (op) {
            case "create_table" -> engine.addTable(TableDeserializer.fromJSON(TableDeserializer.require(fields, "data")));
            case "drop_table" -> engine.dropTable(name);
            case "rename_table" -> engine.renameTable(name, string(fields, "to"));
            default -> engine.requireTable(name).apply(change(op, fields, engine.requireTable(name).getColumns()));
        }
    }

    // A table change read back from its log record
    private static Change change(String op, Map<String, Object> fields, List<Column> columns) {
        return switch (op) {
            case "insert" -> new Change.Insert(rows(fields, columns), number(fields, "nextAutoIncrement"));
            case "update" -> new Change.Update(rows(fields, columns), number(fields, "nextAutoIncrement"));
            case "delete" -> new Change.Delete(TableDeserializer.asArray(TableDeserializer.require(fields, "ids"), "ids")
                    .stream().map(TableDeserializer::asId).toList());
            case "add_column" -> new Change.AddColumn(TableDeserializer.columnFromJSON(TableDeserializer.require(fields, "column")));
            case "drop_column" -> new Change.DropColumn(string(fields, "column"));
            case "rename_column" -> new Change.RenameColumn(string(fields, "from"), string(fields, "to"));
            case "create_index" -> new Change.CreateIndex(string(fields, "name"),
                    TableDeserializer.asArray(TableDeserializer.require(fields, "columns"), "columns").stream()
                            .map(column -> TableDeserializer.asString(column, "index column")).toList(),
                    TableDeserializer.require(fields, "unique") == Boolean.TRUE);
            case "drop_index" -> new Change.DropIndex(string(fields, "name"));
            default -> throw new IllegalArgumentException("Unknown op '" + op + "'");
        };
    }

    // [[id, {row}], ...] in the table's column types
    private static Map<Long, Row> rows(Map<String, Object> fields, List<Column> columns) {
        Map<Long, Row> rows = new LinkedHashMap<>();
        for (Object item : TableDeserializer.asArray(TableDeserializer.require(fields, "rows"), "rows")) {
            List<Object> pair = TableDeserializer.asArray(item, "row");
            if (pair.size() != 2) throw new IllegalArgumentException("Expected [id, row]");
            rows.put(TableDeserializer.asId(pair.get(0)), TableDeserializer.rowFromJSON(pair.get(1), columns));
        }
        return rows;
    }

    private static String string(Map<String, Object> fields, String key) {
        return TableDeserializer.asString(TableDeserializer.require(fields, key), key);
    }

    private static long number(Map<String, Object> fields, String key) {
        return TableDeserializer.asId(TableDeserializer.require(fields, key));
    }

    // Journal: each method writes one record and returns once it's on disk

    @Override
    public void changed(Table table, Change change) {
        StringBuilder sb = new StringBuilder();
        String op;
        if (change instanceof Change.Insert insert) {
            op = "insert";
            sb.append("\"rows\": ").append(rowsToJSON(insert.rows())).append(", \"nextAutoIncrement\": ").append(insert.nextAutoIncrement());
        } else if (change instanceof Change.Update update) {
            op = "update";
            sb.append("\"rows\": ").append(rowsToJSON(update.rows())).append(", \"nextAutoIncrement\": ").append(update.nextAutoIncrement());
        } else if (change instanceof Change.Delete delete) {
            op = "delete";
            sb.append("\"ids\": [").append(delete.ids().stream().map(String::valueOf).collect(Collectors.joining(", "))).append("]");
        } else if (change instanceof Change.AddColumn add) {
            op = "add_column";
            sb.append("\"column\": ").append(TableSerializer.columnToJSON(add.column()));
        } else if (change instanceof Change.DropColumn drop) {
            op = "drop_column";
            sb.append("\"column\": ").append(TableSerializer.quote(drop.column()));
        } else if (change instanceof Change.RenameColumn rename) {
            op = "rename_column";
            sb.append("\"from\": ").append(TableSerializer.quote(rename.from())).append(", \"to\": ").append(TableSerializer.quote(rename.to()));
        } else if (change instanceof Change.CreateIndex create) {
            op = "create_index";
            sb.append("\"name\": ").append(TableSerializer.quote(create.name())).append(", \"columns\": [")
              .append(create.columns().stream().map(TableSerializer::quote).collect(Collectors.joining(", ")))
              .append("], \"unique\": ").append(create.unique());
        } else if (change instanceof Change.DropIndex drop) {
            op = "drop_index";
            sb.append("\"name\": ").append(TableSerializer.quote(drop.name()));
        } else {
            throw new IllegalStateException("Unknown change " + change);
        }
        write(op, table.getName(), sb.toString());
    }

    @Override
    public void created(Table table) {
        write("create_table", table.getName(), "\"data\": " + TableSerializer.toCompactJSON(table));
    }

    @Override
    public void dropped(String table) {
        write("drop_table", table, null);
    }

    @Override
    public void renamed(String from, String to) {
        write("rename_table", from, "\"to\": " + TableSerializer.quote(to));
    }

    private void write(String op, String table, String rest) {
        // Safe here: every earlier change is applied, and this one hasn't changed anything yet
        checkpointIfLarge();
        long lsn = lastLsn + 1;
        log.append("{\"lsn\": " + lsn + ", \"op\": " + TableSerializer.quote(op) + ", \"table\": " + TableSerializer.quote(table)
                + (rest == null ? "" : ", " + rest) + "}");
        lastLsn = lsn;
    }

    private static String rowsToJSON(Map<Long, Row> rows) {
        return rows.entrySet().stream()
                .map(entry -> "[" + entry.getKey() + ", " + TableSerializer.rowToJSON(entry.getValue()) + "]")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    // Writes every table to a new snapshot and empties the log (CHECKPOINT). The snapshot is written to a temporary file
    // and renamed over the old one, so a crash leaves either the old snapshot or the new one, never half of one.
    @Override
    public void checkpoint() {
        Path snapshot = dir.resolve(SNAPSHOT);
        Path temporary = dir.resolve(SNAPSHOT + ".tmp");
        List<String> tables = new ArrayList<>();
        for (Table table : engine.getTables()) tables.add(TableSerializer.toJSON(table, true).strip());
        String json = "{\"lsn\": " + lastLsn + ", \"tables\": [\n" + String.join(",\n", tables) + "\n]}\n";
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, snapshot, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory();
            log.clear();
        } catch (IOException e) {
            throw new StorageException("Checkpoint failed, the log still has every change", e);
        }
    }

    // A checkpoint once the log reaches the size limit. If it fails, the log still has every change, so the
    // write goes ahead with a warning, and the next try waits until the log has grown by the limit again.
    private void checkpointIfLarge() {
        try {
            if (log.size() < nextCheckpointAt) return;
            checkpoint();
            nextCheckpointAt = checkpointSize;
        } catch (IOException | StorageException e) {
            System.out.println("Warning: automatic checkpoint failed, the log keeps growing: " + e.getMessage());
            try {
                nextCheckpointAt = log.size() + checkpointSize;
            } catch (IOException ignored) {
                nextCheckpointAt += checkpointSize;
            }
        }
    }

    // Makes the rename itself durable. Not every system can open a folder for this, and then it's skipped.
    private void forceDirectory() {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            // Best effort
        }
    }

    // Closes the files without a checkpoint: everything is already safe in the log
    @Override
    public void close() throws IOException {
        engine.setJournal(Journal.NONE);
        try {
            log.close();
        } finally {
            lockChannel.close();
        }
    }
}
