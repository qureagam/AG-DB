package com.agdatabase;

/**
 * Thrown when an AG database file is malformed, truncated, or contains invalid magic bytes/header.
 */
public class DatabaseCorruptFileException extends DatabaseException {

    public DatabaseCorruptFileException(String message) {
        super(message);
    }

    public DatabaseCorruptFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
