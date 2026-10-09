package com.agdatabase;

/**
 * Thrown when an attempt is made to insert a key using {@code add()} that already exists in the database.
 */
public class DatabaseDuplicateKeyException extends DatabaseException {

    private final String key;

    public DatabaseDuplicateKeyException(String key) {
        super("Key already exists: '" + key + "'. Use put() or update() to modify existing keys.");
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
