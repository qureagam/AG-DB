package com.agdatabase;

import javax.crypto.SecretKey;
import java.nio.file.Path;
import java.util.*;

/**
 * AG Database — A lightweight, encrypted, HashMap-based key-value database engine.
 * <p>
 * Provides an intuitive {@link HashMap}-like API backed by disk persistence
 * in custom encrypted {@code .ag} files using AES-256-GCM and PBKDF2-HMAC-SHA-256.
 * </p>
 *
 * <pre>{@code
 * try (Database db = new Database("mydata", "myPassword")) {
 *     db.add("username", "Alex");
 *     db.add("age", 18);
 *     db.update("age", 19);
 *     db.save();
 * }
 * }</pre>
 */
public class Database implements AutoCloseable {

    private static final char[] DEFAULT_EMPTY_PASSWORD = "AG_DATABASE_DEFAULT_PASSPHRASE".toCharArray();

    private final String name;
    private final DatabaseConfig config;
    private final DatabaseFile databaseFile;
    private final Map<String, Object> storage = new HashMap<>();

    private char[] password;
    private boolean dirty = false;
    private boolean closed = false;

    /**
     * Opens or creates an AG Database with default settings and a default internal key.
     *
     * @param name database name (e.g. "mydata")
     * @throws DatabaseException if file is corrupt or locked
     */
    public Database(String name) {
        this(name, DEFAULT_EMPTY_PASSWORD.clone(), DatabaseConfig.defaultConfig());
    }

    /**
     * Opens or creates an AG Database with a String password and default configuration.
     *
     * @param name     database name
     * @param password encryption password
     */
    public Database(String name, String password) {
        this(name, password != null ? password.toCharArray() : null, DatabaseConfig.defaultConfig());
    }

    /**
     * Opens or creates an AG Database with a char[] password and default configuration.
     *
     * @param name     database name
     * @param password encryption password
     */
    public Database(String name, char[] password) {
        this(name, password, DatabaseConfig.defaultConfig());
    }

    /**
     * Opens or creates an AG Database with a String password and custom configuration.
     *
     * @param name     database name
     * @param password encryption password
     * @param config   database configuration
     */
    public Database(String name, String password, DatabaseConfig config) {
        this(name, password != null ? password.toCharArray() : null, config);
    }

    /**
     * Opens or creates an AG Database with a char[] password and custom configuration.
     *
     * @param name     database name
     * @param password encryption password
     * @param config   database configuration
     */
    public Database(String name, char[] password, DatabaseConfig config) {
        DatabaseValidator.validateDatabaseName(name);
        this.name = name;
        this.config = config != null ? config : DatabaseConfig.defaultConfig();
        this.password = password != null ? password.clone() : DEFAULT_EMPTY_PASSWORD.clone();

        this.databaseFile = new DatabaseFile(this.config.getDirectory(), this.name, this.config.isFileLocking());

        try {
            loadExistingData();
        } catch (Throwable t) {
            // Ensure lock is released if opening/loading fails (e.g. wrong password)
            this.databaseFile.close();
            if (this.config.isWipePasswordOnClose()) {
                DatabaseCrypto.wipe(this.password);
            }
            this.closed = true;
            throw t;
        }
    }

    /**
     * Loads existing encrypted data if the file exists on disk.
     */
    private synchronized void loadExistingData() {
        if (!databaseFile.exists()) {
            return;
        }

        DatabaseFile.EncryptedSnapshot snapshot = databaseFile.readSnapshot();
        if (snapshot == null) {
            return;
        }

        // Derive key using the file's salt and PBKDF2 iterations
        SecretKey key = DatabaseCrypto.deriveKey(this.password, snapshot.salt(), snapshot.iterations());
        byte[] decryptedBytes = DatabaseCrypto.decrypt(snapshot.ciphertext(), key, snapshot.nonce());

        Map<String, Object> loadedMap = DatabaseSerializer.deserialize(decryptedBytes);
        this.storage.clear();
        this.storage.putAll(loadedMap);
        this.dirty = false;
    }

    /**
     * Inserts a new key-value pair.
     *
     * @param key   non-null string key
     * @param value supported data value
     * @throws DatabaseDuplicateKeyException if the key already exists
     * @throws IllegalArgumentException      if key is null
     * @throws DatabaseSerializationException if value type is unsupported
     */
    public synchronized void add(String key, Object value) {
        ensureOpen();
        DatabaseValidator.validateKey(key);
        DatabaseValidator.assertSupportedType(value);

        if (storage.containsKey(key)) {
            throw new DatabaseDuplicateKeyException(key);
        }

        storage.put(key, value);
        markDirty();
        if (config.isAutoSave()) {
            save();
        }
    }

    /**
     * Inserts or replaces a key-value pair, like {@link Map#put(Object, Object)}.
     *
     * @param key   non-null string key
     * @param value supported data value
     * @return previous value associated with key, or {@code null}
     */
    public synchronized Object put(String key, Object value) {
        ensureOpen();
        DatabaseValidator.validateKey(key);
        DatabaseValidator.assertSupportedType(value);

        Object previous = storage.put(key, value);
        markDirty();
        if (config.isAutoSave()) {
            save();
        }
        return previous;
    }

    /**
     * Updates an existing key with a new value.
     *
     * @param key      non-null string key
     * @param newValue new value to assign
     * @return previous value associated with the key
     * @throws DatabaseKeyNotFoundException if the key does not exist
     */
    public synchronized Object update(String key, Object newValue) {
        ensureOpen();
        DatabaseValidator.validateKey(key);
        DatabaseValidator.assertSupportedType(newValue);

        if (!storage.containsKey(key)) {
            throw new DatabaseKeyNotFoundException(key);
        }

        Object previous = storage.put(key, newValue);
        markDirty();
        if (config.isAutoSave()) {
            save();
        }
        return previous;
    }

    /**
     * Retrieves the value associated with a key.
     *
     * @param key non-null string key
     * @return associated value, or {@code null} if not found
     */
    public synchronized Object get(String key) {
        ensureOpen();
        DatabaseValidator.validateKey(key);
        return storage.get(key);
    }

    /**
     * Retrieves the value associated with a key, or returns defaultValue if not present.
     *
     * @param key          non-null string key
     * @param defaultValue default value
     * @return associated value or defaultValue
     */
    public synchronized Object getOrDefault(String key, Object defaultValue) {
        ensureOpen();
        DatabaseValidator.validateKey(key);
        return storage.getOrDefault(key, defaultValue);
    }

    /**
     * Inserts value if the specified key is not already present.
     *
     * @param key   non-null string key
     * @param value value to associate
     * @return existing value or null if newly inserted
     */
    public synchronized Object putIfAbsent(String key, Object value) {
        ensureOpen();
        DatabaseValidator.validateKey(key);
        DatabaseValidator.assertSupportedType(value);

        if (!storage.containsKey(key)) {
            storage.put(key, value);
            markDirty();
            if (config.isAutoSave()) {
                save();
            }
            return null;
        }
        return storage.get(key);
    }

    /**
     * Checks if the database contains the given key.
     *
     * @param key non-null string key
     * @return true if key exists
     */
    public synchronized boolean contains(String key) {
        ensureOpen();
        DatabaseValidator.validateKey(key);
        return storage.containsKey(key);
    }

    /**
     * Alias for {@link #contains(String)}.
     *
     * @param key non-null string key
     * @return true if key exists
     */
    public synchronized boolean containsKey(String key) {
        return contains(key);
    }

    /**
     * Checks if the database contains the given value.
     *
     * @param value value to check
     * @return true if value exists in database
     */
    public synchronized boolean containsValue(Object value) {
        ensureOpen();
        return storage.containsValue(value);
    }

    /**
     * Deletes a key from the database.
     *
     * @param key non-null string key
     * @return true if key was present and removed, false otherwise
     */
    public synchronized boolean delete(String key) {
        ensureOpen();
        DatabaseValidator.validateKey(key);
        if (storage.containsKey(key)) {
            storage.remove(key);
            markDirty();
            if (config.isAutoSave()) {
                save();
            }
            return true;
        }
        return false;
    }

    /**
     * Removes a key from the database and returns its previous value.
     *
     * @param key non-null string key
     * @return previous value or null if key was not present
     */
    public synchronized Object remove(String key) {
        ensureOpen();
        DatabaseValidator.validateKey(key);
        if (storage.containsKey(key)) {
            Object prev = storage.remove(key);
            markDirty();
            if (config.isAutoSave()) {
                save();
            }
            return prev;
        }
        return null;
    }

    /**
     * Removes all entries from the database.
     */
    public synchronized void clear() {
        ensureOpen();
        if (!storage.isEmpty()) {
            storage.clear();
            markDirty();
            if (config.isAutoSave()) {
                save();
            }
        }
    }

    /**
     * Returns the number of entries in the database.
     *
     * @return entry count
     */
    public synchronized int size() {
        ensureOpen();
        return storage.size();
    }

    /**
     * Returns true if database contains no entries.
     *
     * @return true if empty
     */
    public synchronized boolean isEmpty() {
        ensureOpen();
        return storage.isEmpty();
    }

    /**
     * Returns an unmodifiable snapshot set of all keys in the database.
     *
     * @return unmodifiable Set of keys
     */
    public synchronized Set<String> keys() {
        ensureOpen();
        return Collections.unmodifiableSet(new HashSet<>(storage.keySet()));
    }

    /**
     * Returns an unmodifiable snapshot collection of all values in the database.
     *
     * @return unmodifiable Collection of values
     */
    public synchronized Collection<Object> values() {
        ensureOpen();
        return Collections.unmodifiableCollection(new ArrayList<>(storage.values()));
    }

    /**
     * Returns an unmodifiable snapshot map of all key-value entries.
     *
     * @return unmodifiable Map
     */
    public synchronized Map<String, Object> getAll() {
        ensureOpen();
        return Collections.unmodifiableMap(new HashMap<>(storage));
    }

    /**
     * Persists the database contents to disk in encrypted .ag format.
     */
    public synchronized void save() {
        ensureOpen();

        byte[] serializedData = DatabaseSerializer.serialize(storage);

        byte[] salt = DatabaseCrypto.generateSalt();
        byte[] nonce = DatabaseCrypto.generateNonce();
        int iterations = config.getPbkdf2Iterations();

        SecretKey key = DatabaseCrypto.deriveKey(this.password, salt, iterations);
        byte[] ciphertext = DatabaseCrypto.encrypt(serializedData, key, nonce);

        DatabaseFile.EncryptedSnapshot snapshot = new DatabaseFile.EncryptedSnapshot(salt, iterations, nonce, ciphertext);
        databaseFile.writeSnapshot(snapshot);
        this.dirty = false;
    }

    /**
     * Reloads data from disk, discarding any unsaved in-memory modifications.
     */
    public synchronized void reload() {
        ensureOpen();
        loadExistingData();
    }

    /**
     * Returns true if database has unsaved modifications.
     *
     * @return true if dirty
     */
    public synchronized boolean isDirty() {
        ensureOpen();
        return dirty;
    }

    /**
     * Returns the database name.
     *
     * @return database name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the database file path.
     *
     * @return Path to .ag file
     */
    public Path getFilePath() {
        return databaseFile.getFilePath();
    }

    /**
     * Returns true if database has been closed.
     *
     * @return true if closed
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    private void markDirty() {
        this.dirty = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new DatabaseClosedException("Database '" + name + "' is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        try {
            if (dirty) {
                save();
            }
        } finally {
            databaseFile.close();
            if (config.isWipePasswordOnClose()) {
                DatabaseCrypto.wipe(password);
            }
            closed = true;
        }
    }
}
