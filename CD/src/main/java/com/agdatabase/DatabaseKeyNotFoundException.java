package com.agdatabase;

/**
 * Thrown when an attempt is made to update a key using {@code update()} that does not exist in the database.
 */
public class DatabaseKeyNotFoundException extends DatabaseException {

    private final String key;

    public DatabaseKeyNotFoundException(String key) {
        super("Key not found: '" + key + "'. Use add() or put() to insert new keys.");
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
