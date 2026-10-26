package db.model;

import java.util.List;
import java.util.Map;

// One statement's effect on a table, worked out and checked before anything changes. Table writes it to its
// journal and then applies it, and recovery applies the same change again, so the two can't disagree.
// Rows are keyed by their internal id, the one indexes use.
public sealed interface Change {
    // New rows, and the AUTO_INCREMENT counter after them
    record Insert(Map<Long, Row> rows, long nextAutoIncrement) implements Change {}

    // Each row's new version, and the AUTO_INCREMENT counter after them (a key set by hand moves it)
    record Update(Map<Long, Row> rows, long nextAutoIncrement) implements Change {}

    record Delete(List<Long> ids) implements Change {}

    record AddColumn(Column column) implements Change {}

    record DropColumn(String column) implements Change {}

    record RenameColumn(String from, String to) implements Change {}

    record CreateIndex(String name, List<String> columns, boolean unique) implements Change {}

    record DropIndex(String name) implements Change {}
}
