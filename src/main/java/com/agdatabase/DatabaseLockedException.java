package com.agdatabase;

/**
 * Thrown when an AG database file is currently locked by another process or database instance.
 */
public class DatabaseLockedException extends DatabaseException {

    public DatabaseLockedException(String message) {
        super(message);
    }

    public DatabaseLockedException(String message, Throwable cause) {
        super(message, cause);
    }
}
