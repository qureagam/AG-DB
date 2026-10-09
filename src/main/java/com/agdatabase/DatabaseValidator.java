package com.agdatabase;

import java.util.Collection;
import java.util.Map;

/**
 * Validates keys, names, and values stored in AG Database.
 */
public final class DatabaseValidator {

    private DatabaseValidator() {
    }

    /**
     * Validates that the database name is not null or blank.
     *
     * @param name database name
     */
    public static void validateDatabaseName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Database name must not be null or blank");
        }
    }

    /**
     * Validates that a key is not null.
     *
     * @param key the key to check
     */
    public static void validateKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Database key must not be null");
        }
    }

    /**
     * Checks whether a value's type is supported by AG Database.
     *
     * @param value object to validate
     * @return true if the type is supported, false otherwise
     */
    public static boolean isSupportedType(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String ||
            value instanceof Integer ||
            value instanceof Long ||
            value instanceof Double ||
            value instanceof Float ||
            value instanceof Boolean ||
            value instanceof Short ||
            value instanceof Byte ||
            value instanceof Character ||
            value instanceof byte[]) {
            return true;
        }
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                if (!isSupportedType(item)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof Map<?, ?>) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    return false;
                }
                if (!isSupportedType(entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Asserts that a value type is supported, throwing {@link DatabaseSerializationException} if not.
     *
     * @param value object to validate
     */
    public static void assertSupportedType(Object value) {
        if (!isSupportedType(value)) {
            String typeName = value != null ? value.getClass().getName() : "null";
            throw new DatabaseSerializationException("Unsupported data type: " + typeName +
                    ". AG Database supports String, Integer, Long, Double, Float, Boolean, Short, Byte, Character, byte[], null, and nested Lists/Maps of supported types.");
        }
    }
}
