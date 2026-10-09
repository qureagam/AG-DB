package com.agdatabase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseTest {

    @TempDir
    Path tempDir;

    private String dbName;
    private DatabaseConfig config;

    @BeforeEach
    void setUp() {
        dbName = "testdb_" + UUID.randomUUID().toString().replace("-", "");
        config = DatabaseConfig.builder()
                .directory(tempDir)
                // Use 2,000 iterations for test speed while still testing PBKDF2 logic
                .pbkdf2Iterations(2000)
                .build();
    }

    @AfterEach
    void tearDown() {
        // Clean up files if any left
    }

    @Test
    void testCreateAndBasicCRUD() {
        try (Database db = new Database(dbName, "pass123", config)) {
            assertTrue(db.isEmpty());
            assertEquals(0, db.size());

            db.add("username", "Alex");
            db.add("age", 18);
            db.add("isStudent", true);

            assertEquals(3, db.size());
            assertFalse(db.isEmpty());

            assertEquals("Alex", db.get("username"));
            assertEquals(18, db.get("age"));
            assertEquals(true, db.get("isStudent"));

            assertTrue(db.contains("username"));
            assertTrue(db.containsKey("age"));
            assertTrue(db.containsValue(18));
            assertFalse(db.contains("nonExistent"));

            // Update
            db.update("age", 19);
            assertEquals(19, db.get("age"));

            // Put (insert and replace)
            db.put("country", "Canada");
            assertEquals("Canada", db.get("country"));
            db.put("country", "USA");
            assertEquals("USA", db.get("country"));

            // Put if absent
            db.putIfAbsent("country", "UK");
            assertEquals("USA", db.get("country"));
            db.putIfAbsent("city", "New York");
            assertEquals("New York", db.get("city"));

            // Get or default
            assertEquals("DefaultValue", db.getOrDefault("missingKey", "DefaultValue"));

            // Delete / Remove
            assertTrue(db.delete("isStudent"));
            assertFalse(db.contains("isStudent"));
            assertFalse(db.delete("isStudent")); // already deleted

            assertEquals("USA", db.remove("country"));
            assertNull(db.remove("country"));

            // Clear
            db.clear();
            assertEquals(0, db.size());
            assertTrue(db.isEmpty());
        }
    }

    @Test
    void testDuplicateKeyThrowsException() {
        try (Database db = new Database(dbName, "pass123", config)) {
            db.add("uniqueKey", "firstValue");

            DatabaseDuplicateKeyException ex = assertThrows(DatabaseDuplicateKeyException.class, () -> {
                db.add("uniqueKey", "secondValue");
            });

            assertEquals("uniqueKey", ex.getKey());
            assertEquals("firstValue", db.get("uniqueKey"));
        }
    }

    @Test
    void testUpdateMissingKeyThrowsException() {
        try (Database db = new Database(dbName, "pass123", config)) {
            DatabaseKeyNotFoundException ex = assertThrows(DatabaseKeyNotFoundException.class, () -> {
                db.update("nonExistingKey", "value");
            });

            assertEquals("nonExistingKey", ex.getKey());
        }
    }

    @Test
    void testNullKeyRejection() {
        try (Database db = new Database(dbName, "pass123", config)) {
            assertThrows(IllegalArgumentException.class, () -> db.add(null, "value"));
            assertThrows(IllegalArgumentException.class, () -> db.put(null, "value"));
            assertThrows(IllegalArgumentException.class, () -> db.get(null));
            assertThrows(IllegalArgumentException.class, () -> db.update(null, "value"));
            assertThrows(IllegalArgumentException.class, () -> db.delete(null));
        }
    }

    @Test
    void testAllSupportedDataTypes() {
        byte[] sampleBytes = new byte[]{1, 2, 3, 4, 5, -1, -128, 127};
        List<Object> sampleList = List.of("stringInList", 42, true, 3.14);
        Map<String, Object> sampleMap = Map.of("nestedKey1", "nestedVal", "nestedKey2", 999L);

        try (Database db = new Database(dbName, "pass123", config)) {
            db.add("str", "Hello, World!");
            db.add("int", 123456);
            db.add("long", 9876543210123L);
            db.add("double", 3.141592653589793);
            db.add("float", 2.71828f);
            db.add("bool", true);
            db.add("short", (short) 32000);
            db.add("byte", (byte) 120);
            db.add("char", 'Z');
            db.add("bytes", sampleBytes);
            db.add("nullVal", null);
            db.add("list", sampleList);
            db.add("map", sampleMap);
            db.save();
        }

        // Reopen and assert exact types
        try (Database db = new Database(dbName, "pass123", config)) {
            assertInstanceOf(String.class, db.get("str"));
            assertEquals("Hello, World!", db.get("str"));

            assertInstanceOf(Integer.class, db.get("int"));
            assertEquals(123456, db.get("int"));

            assertInstanceOf(Long.class, db.get("long"));
            assertEquals(9876543210123L, db.get("long"));

            assertInstanceOf(Double.class, db.get("double"));
            assertEquals(3.141592653589793, (Double) db.get("double"), 1e-9);

            assertInstanceOf(Float.class, db.get("float"));
            assertEquals(2.71828f, (Float) db.get("float"), 1e-5);

            assertInstanceOf(Boolean.class, db.get("bool"));
            assertEquals(true, db.get("bool"));

            assertInstanceOf(Short.class, db.get("short"));
            assertEquals((short) 32000, db.get("short"));

            assertInstanceOf(Byte.class, db.get("byte"));
            assertEquals((byte) 120, db.get("byte"));

            assertInstanceOf(Character.class, db.get("char"));
            assertEquals('Z', db.get("char"));

            assertInstanceOf(byte[].class, db.get("bytes"));
            assertArrayEquals(sampleBytes, (byte[]) db.get("bytes"));

            assertNull(db.get("nullVal"));
            assertTrue(db.contains("nullVal")); // Key exists even though value is null

            assertInstanceOf(List.class, db.get("list"));
            List<?> loadedList = (List<?>) db.get("list");
            assertEquals("stringInList", loadedList.get(0));
            assertEquals(42, loadedList.get(1));

            assertInstanceOf(Map.class, db.get("map"));
            Map<?, ?> loadedMap = (Map<?, ?>) db.get("map");
            assertEquals("nestedVal", loadedMap.get("nestedKey1"));
            assertEquals(999L, loadedMap.get("nestedKey2"));
        }
    }

    @Test
    void testUnsupportedDataTypeThrowsException() {
        try (Database db = new Database(dbName, "pass123", config)) {
            class CustomUnsupportedClass {
                final int x = 10;
            }

            assertThrows(DatabaseSerializationException.class, () -> {
                db.add("badKey", new CustomUnsupportedClass());
            });

            assertThrows(DatabaseSerializationException.class, () -> {
                db.put("badKey2", Thread.currentThread());
            });
        }
    }

    @Test
    void testSaveAndReopenPersistence() {
        try (Database db = new Database(dbName, "pass123", config)) {
            db.add("hero", "Superman");
            db.add("power", 100);
            db.save();
        }

        try (Database db = new Database(dbName, "pass123", config)) {
            assertEquals("Superman", db.get("hero"));
            assertEquals(100, db.get("power"));
            db.update("power", 150);
            db.add("sidekick", "Jimmy");
            db.save();
        }

        try (Database db = new Database(dbName, "pass123", config)) {
            assertEquals("Superman", db.get("hero"));
            assertEquals(150, db.get("power"));
            assertEquals("Jimmy", db.get("sidekick"));
        }
    }

    @Test
    void testWrongPasswordThrowsCryptoException() {
        try (Database db = new Database(dbName, "CorrectPassword123", config)) {
            db.add("secret", "classified_info");
            db.save();
        }

        DatabaseCryptoException ex = assertThrows(DatabaseCryptoException.class, () -> {
            new Database(dbName, "WrongPassword!", config);
        });

        assertTrue(ex.getMessage().contains("Decryption failed") || ex.getMessage().contains("password"));
    }

    @Test
    void testTamperedFileDetection() throws IOException {
        Path filePath;
        try (Database db = new Database(dbName, "password", config)) {
            db.add("key1", "val1");
            db.save();
            filePath = db.getFilePath();
        }

        // Tamper with the ciphertext byte at the end of the file
        byte[] fileBytes = Files.readAllBytes(filePath);
        fileBytes[fileBytes.length - 5] ^= 0xFF; // Flip bits in ciphertext / GCM auth tag
        Files.write(filePath, fileBytes);

        assertThrows(DatabaseCryptoException.class, () -> {
            new Database(dbName, "password", config);
        });
    }

    @Test
    void testCorruptedMagicBytesDetection() throws IOException {
        Path filePath;
        try (Database db = new Database(dbName, "password", config)) {
            db.add("key1", "val1");
            db.save();
            filePath = db.getFilePath();
        }

        // Overwrite magic bytes
        byte[] fileBytes = Files.readAllBytes(filePath);
        fileBytes[0] = 'X';
        Files.write(filePath, fileBytes);

        assertThrows(DatabaseCorruptFileException.class, () -> {
            new Database(dbName, "password", config);
        });
    }

    @Test
    void testEmptyDatabasePersistence() {
        try (Database db = new Database(dbName, "emptyPass", config)) {
            db.save();
        }

        try (Database db = new Database(dbName, "emptyPass", config)) {
            assertTrue(db.isEmpty());
            assertEquals(0, db.size());
            db.add("newKey", "newValue");
            db.save();
        }

        try (Database db = new Database(dbName, "emptyPass", config)) {
            assertEquals(1, db.size());
            assertEquals("newValue", db.get("newKey"));
        }
    }

    @Test
    void testLargeValues() {
        String largeString = "A".repeat(1_000_000); // 1 MB string
        byte[] largeBytes = new byte[1_000_000];     // 1 MB byte array
        Arrays.fill(largeBytes, (byte) 42);

        try (Database db = new Database(dbName, "largeDataPass", config)) {
            db.add("largeStr", largeString);
            db.add("largeBytes", largeBytes);
            db.save();
        }

        try (Database db = new Database(dbName, "largeDataPass", config)) {
            assertEquals(largeString, db.get("largeStr"));
            assertArrayEquals(largeBytes, (byte[]) db.get("largeBytes"));
        }
    }

    @Test
    void testDefensiveCopies() {
        try (Database db = new Database(dbName, "pass", config)) {
            db.add("k1", "v1");
            db.add("k2", "v2");

            Set<String> keys = db.keys();
            assertThrows(UnsupportedOperationException.class, () -> keys.add("k3"));

            Collection<Object> values = db.values();
            assertThrows(UnsupportedOperationException.class, () -> values.add("v3"));

            Map<String, Object> all = db.getAll();
            assertThrows(UnsupportedOperationException.class, () -> all.put("k3", "v3"));
        }
    }

    @Test
    void testFileLockingPreventsConcurrentAccess() {
        try (Database db1 = new Database(dbName, "pass", config)) {
            // Attempting to open same database with fileLocking enabled should throw DatabaseLockedException
            assertThrows(DatabaseLockedException.class, () -> {
                new Database(dbName, "pass", config);
            });
        }

        // After db1 is closed, db2 should successfully open
        assertDoesNotThrow(() -> {
            try (Database db2 = new Database(dbName, "pass", config)) {
                assertEquals(0, db2.size());
            }
        });
    }

    @Test
    void testReloadDiscardsUnsavedModifications() {
        try (Database db = new Database(dbName, "pass", config)) {
            db.add("k1", "savedValue");
            db.save();

            db.put("k1", "unsavedModification");
            db.add("k2", "unsavedNewKey");
            assertEquals("unsavedModification", db.get("k1"));
            assertEquals("unsavedNewKey", db.get("k2"));

            db.reload();

            assertEquals("savedValue", db.get("k1"));
            assertFalse(db.contains("k2"));
        }
    }

    @Test
    void testAutoSaveFeature() {
        DatabaseConfig autoSaveConfig = DatabaseConfig.builder()
                .directory(tempDir)
                .pbkdf2Iterations(2000)
                .autoSave(true)
                .build();

        String autoDbName = "autoSaveDb";

        // Without calling db.save(), autoSave should persist
        try (Database db = new Database(autoDbName, "pass", autoSaveConfig)) {
            db.add("autoKey", "autoValue");
            assertFalse(db.isDirty()); // Auto-save clears dirty immediately
        }

        try (Database db = new Database(autoDbName, "pass", autoSaveConfig)) {
            assertEquals("autoValue", db.get("autoKey"));
        }
    }

    @Test
    void testOperationOnClosedDatabaseThrows() {
        Database db = new Database(dbName, "pass", config);
        db.add("k1", "v1");
        db.close();

        assertTrue(db.isClosed());
        assertThrows(DatabaseClosedException.class, () -> db.get("k1"));
        assertThrows(DatabaseClosedException.class, () -> db.add("k2", "v2"));
        assertThrows(DatabaseClosedException.class, () -> db.put("k1", "v2"));
        assertThrows(DatabaseClosedException.class, () -> db.update("k1", "v2"));
        assertThrows(DatabaseClosedException.class, () -> db.delete("k1"));
        assertThrows(DatabaseClosedException.class, () -> db.save());
        assertThrows(DatabaseClosedException.class, () -> db.reload());
    }

    @Test
    void testCharArrayPassword() {
        char[] password = new char[]{'S', 'e', 'c', 'u', 'r', 'e', '!'};
        try (Database db = new Database(dbName, password, config)) {
            db.add("charPassKey", "val");
            db.save();
        }

        try (Database db = new Database(dbName, password, config)) {
            assertEquals("val", db.get("charPassKey"));
        }
    }
}
