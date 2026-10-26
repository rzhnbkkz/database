package db.storage;

// The data folder couldn't be read or written. When a write to the log fails, the change it was for didn't happen.
public class StorageException extends RuntimeException {
    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message + ": " + cause.getMessage(), cause);
    }
}
