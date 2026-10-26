package db.model;

// Where changes are written before they happen: the write-ahead log of a data folder, or nowhere for an
// in-memory database. A method that returns has made the change durable; one that throws means the change
// must not happen.
public interface Journal {
    Journal NONE = new Journal() {};

    default void changed(Table table, Change change) {}

    // A new table, with whatever rows it already has (LOAD)
    default void created(Table table) {}

    default void dropped(String table) {}

    default void renamed(String from, String to) {}

    // A transaction: the changes until commit are written together, as one record, or not at all
    default void begin() {}

    default void commit() {}

    default void rollback() {}

    // Saves everything so the log can start over
    default void checkpoint() {
        throw new IllegalArgumentException("CHECKPOINT needs a data folder, this database is only in memory");
    }
}
