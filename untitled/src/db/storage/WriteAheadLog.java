package db.storage;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

// An append-only file of records, one per line: the CRC32 of the record's JSON in 8 hex digits, a space, the
// JSON, and a newline. Each append is forced to disk before it returns, so a record that was acknowledged
// survives a crash. A crash during an append leaves at most one incomplete record at the end, which reading
// drops. A damaged record with good ones after it is corruption, not a crash, and is refused.
public class WriteAheadLog implements Closeable {
    // A record read back: its log sequence number and its JSON fields
    public record Record(long lsn, Map<String, Object> fields) {}

    private final Path path;
    private final FileChannel channel;
    // Set when a failed append couldn't be undone, so the file may end in a partial record
    private boolean broken = false;

    public WriteAheadLog(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    // Every complete record, in order. An incomplete record at the end, from a crash during an append, is
    // cut off so new records follow the last good one.
    public List<Record> readAll() throws IOException {
        byte[] bytes = new byte[(int) channel.size()];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining() && channel.read(buffer, buffer.position()) >= 0) {}
        List<Record> records = new ArrayList<>();
        int start = 0;
        while (start < bytes.length) {
            int end = indexOf(bytes, (byte) '\n', start);
            Record record = end < 0 ? null : parse(new String(bytes, start, end - start, StandardCharsets.UTF_8));
            if (record == null) {
                if (hasGoodRecordAfter(bytes, end)) {
                    throw new StorageException(path + " is damaged at byte " + start + ", and good records follow it");
                }
                System.out.println("Dropped an incomplete record at the end of " + path + " (" + (bytes.length - start)
                        + " bytes), left by a crash during a write");
                channel.truncate(start);
                channel.force(true);
                break;
            }
            if (!records.isEmpty() && record.lsn() <= records.get(records.size() - 1).lsn()) {
                throw new StorageException(path + " has records out of order at byte " + start);
            }
            records.add(record);
            start = end + 1;
        }
        return records;
    }

    // Adds a record and forces it to disk. If that fails, the file is cut back to how it was.
    public void append(String json) {
        if (broken) throw new StorageException("The log can't be written since an earlier write failed; restart to recover");
        byte[] line = (crc(json) + " " + json + "\n").getBytes(StandardCharsets.UTF_8);
        long size = -1;
        try {
            size = channel.size();
            ByteBuffer buffer = ByteBuffer.wrap(line);
            long position = size;
            while (buffer.hasRemaining()) position += channel.write(buffer, position);
            channel.force(false);
        } catch (IOException e) {
            try {
                if (size >= 0) {
                    channel.truncate(size);
                    channel.force(true);
                }
            } catch (IOException undo) {
                broken = true;
            }
            throw new StorageException("Could not write to " + path + ", nothing was changed", e);
        }
    }

    // Empties the log, once a snapshot holds everything in it
    public void clear() throws IOException {
        channel.truncate(0);
        channel.force(true);
    }

    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    static String crc(String json) {
        CRC32 crc = new CRC32();
        crc.update(json.getBytes(StandardCharsets.UTF_8));
        return String.format("%08x", crc.getValue());
    }

    // null unless the line is a whole record whose checksum matches
    private static Record parse(String line) {
        if (line.length() < 10 || line.charAt(8) != ' ') return null;
        String json = line.substring(9);
        if (!line.substring(0, 8).equals(crc(json))) return null;
        try {
            Map<String, Object> fields = TableDeserializer.asObject(new TableDeserializer.JsonReader(json).readDocument(), "log record");
            return new Record(TableDeserializer.asId(TableDeserializer.require(fields, "lsn")), fields);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean hasGoodRecordAfter(byte[] bytes, int end) {
        if (end < 0) return false;
        for (int start = end + 1; start < bytes.length; ) {
            int next = indexOf(bytes, (byte) '\n', start);
            if (next < 0) return false;
            if (parse(new String(bytes, start, next - start, StandardCharsets.UTF_8)) != null) return true;
            start = next + 1;
        }
        return false;
    }

    private static int indexOf(byte[] bytes, byte b, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == b) return i;
        }
        return -1;
    }
}
