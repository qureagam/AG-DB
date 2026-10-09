package com.agdatabase;

/**
 * Thrown when an operation is attempted on a closed database instance.
 */
public class DatabaseClosedException extends DatabaseException {

    public DatabaseClosedException(String message) {
        super(message);
    }
}
