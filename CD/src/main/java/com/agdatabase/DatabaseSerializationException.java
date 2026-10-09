package com.agdatabase;

/**
 * Thrown when serialization or deserialization fails, such as encountering an unsupported data type,
 * invalid type tag, or payload exceeding size limits.
 */
public class DatabaseSerializationException extends DatabaseException {

    public DatabaseSerializationException(String message) {
        super(message);
    }

    public DatabaseSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
