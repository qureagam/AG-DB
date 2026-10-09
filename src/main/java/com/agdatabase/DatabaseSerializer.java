package com.agdatabase;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Custom binary serializer for AG Database.
 * <p>
 * Uses explicit type tags to preserve exact Java types without using native Java object
 * deserialization or reflection. Enforces size bounds and nesting limits to prevent
 * resource exhaustion.
 * </p>
 */
public final class DatabaseSerializer {

    public static final byte TAG_NULL = 0x00;
    public static final byte TAG_STRING = 0x01;
    public static final byte TAG_INTEGER = 0x02;
    public static final byte TAG_LONG = 0x03;
    public static final byte TAG_DOUBLE = 0x04;
    public static final byte TAG_FLOAT = 0x05;
    public static final byte TAG_BOOLEAN = 0x06;
    public static final byte TAG_SHORT = 0x07;
    public static final byte TAG_BYTE = 0x08;
    public static final byte TAG_CHARACTER = 0x09;
    public static final byte TAG_BYTE_ARRAY = 0x0A;
    public static final byte TAG_LIST = 0x0B;
    public static final byte TAG_MAP = 0x0C;

    public static final int MAX_NESTING_DEPTH = 32;
    public static final int MAX_STRING_BYTES = 64 * 1024 * 1024;    // 64 MB
    public static final int MAX_BYTE_ARRAY_SIZE = 64 * 1024 * 1024; // 64 MB
    public static final int MAX_COLLECTION_SIZE = 1_000_000;

    private DatabaseSerializer() {
    }

    /**
     * Serializes an in-memory map into type-tagged binary format.
     *
     * @param map the data map
     * @return serialized byte array
     */
    public static byte[] serialize(Map<String, Object> map) {
        if (map == null) {
            throw new DatabaseSerializationException("Cannot serialize null database map");
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);

            dos.writeInt(map.size());
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                if (key == null) {
                    throw new DatabaseSerializationException("Database keys must not be null");
                }
                writeString(dos, key);
                writeValue(dos, entry.getValue(), 0);
            }
            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new DatabaseSerializationException("Failed to serialize database: " + e.getMessage(), e);
        }
    }

    /**
     * Deserializes binary data into an in-memory {@code HashMap<String, Object>}.
     *
     * @param data serialized byte array
     * @return reconstructed map
     */
    public static Map<String, Object> deserialize(byte[] data) {
        if (data == null) {
            throw new DatabaseSerializationException("Cannot deserialize null byte array");
        }
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(data);
            DataInputStream dis = new DataInputStream(bais);

            int size = dis.readInt();
            if (size < 0 || size > MAX_COLLECTION_SIZE) {
                throw new DatabaseSerializationException("Invalid database size in payload: " + size);
            }

            Map<String, Object> result = new HashMap<>(size);
            for (int i = 0; i < size; i++) {
                String key = readString(dis);
                Object value = readValue(dis, 0);
                result.put(key, value);
            }

            if (dis.available() > 0) {
                throw new DatabaseSerializationException("Extraneous bytes found after reading database payload");
            }

            return result;
        } catch (IOException e) {
            throw new DatabaseSerializationException("Failed to deserialize database payload: " + e.getMessage(), e);
        }
    }

    private static void writeValue(DataOutputStream dos, Object value, int depth) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            throw new DatabaseSerializationException("Maximum collection nesting depth (" + MAX_NESTING_DEPTH + ") exceeded");
        }

        if (value == null) {
            dos.writeByte(TAG_NULL);
        } else if (value instanceof String s) {
            dos.writeByte(TAG_STRING);
            writeString(dos, s);
        } else if (value instanceof Integer i) {
            dos.writeByte(TAG_INTEGER);
            dos.writeInt(i);
        } else if (value instanceof Long l) {
            dos.writeByte(TAG_LONG);
            dos.writeLong(l);
        } else if (value instanceof Double d) {
            dos.writeByte(TAG_DOUBLE);
            dos.writeDouble(d);
        } else if (value instanceof Float f) {
            dos.writeByte(TAG_FLOAT);
            dos.writeFloat(f);
        } else if (value instanceof Boolean b) {
            dos.writeByte(TAG_BOOLEAN);
            dos.writeBoolean(b);
        } else if (value instanceof Short s) {
            dos.writeByte(TAG_SHORT);
            dos.writeShort(s);
        } else if (value instanceof Byte b) {
            dos.writeByte(TAG_BYTE);
            dos.writeByte(b);
        } else if (value instanceof Character c) {
            dos.writeByte(TAG_CHARACTER);
            dos.writeChar(c);
        } else if (value instanceof byte[] bytes) {
            dos.writeByte(TAG_BYTE_ARRAY);
            writeByteArray(dos, bytes);
        } else if (value instanceof Collection<?> col) {
            dos.writeByte(TAG_LIST);
            writeCollection(dos, col, depth + 1);
        } else if (value instanceof Map<?, ?> m) {
            dos.writeByte(TAG_MAP);
            writeMap(dos, m, depth + 1);
        } else {
            throw new DatabaseSerializationException("Unsupported data type for serialization: " + value.getClass().getName());
        }
    }

    private static Object readValue(DataInputStream dis, int depth) throws IOException {
        if (depth > MAX_NESTING_DEPTH) {
            throw new DatabaseSerializationException("Maximum collection nesting depth (" + MAX_NESTING_DEPTH + ") exceeded");
        }

        byte tag = dis.readByte();
        return switch (tag) {
            case TAG_NULL -> null;
            case TAG_STRING -> readString(dis);
            case TAG_INTEGER -> dis.readInt();
            case TAG_LONG -> dis.readLong();
            case TAG_DOUBLE -> dis.readDouble();
            case TAG_FLOAT -> dis.readFloat();
            case TAG_BOOLEAN -> dis.readBoolean();
            case TAG_SHORT -> dis.readShort();
            case TAG_BYTE -> dis.readByte();
            case TAG_CHARACTER -> dis.readChar();
            case TAG_BYTE_ARRAY -> readByteArray(dis);
            case TAG_LIST -> readList(dis, depth + 1);
            case TAG_MAP -> readMap(dis, depth + 1);
            default -> throw new DatabaseSerializationException("Unknown serialization type tag: 0x" + Integer.toHexString(tag & 0xFF));
        };
    }

    private static void writeString(DataOutputStream dos, String str) throws IOException {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new DatabaseSerializationException("String byte length (" + bytes.length + ") exceeds maximum limit (" + MAX_STRING_BYTES + ")");
        }
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }

    private static String readString(DataInputStream dis) throws IOException {
        int length = dis.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new DatabaseSerializationException("Invalid string length: " + length);
        }
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeByteArray(DataOutputStream dos, byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTE_ARRAY_SIZE) {
            throw new DatabaseSerializationException("byte[] size (" + bytes.length + ") exceeds maximum limit (" + MAX_BYTE_ARRAY_SIZE + ")");
        }
        dos.writeInt(bytes.length);
        dos.write(bytes);
    }

    private static byte[] readByteArray(DataInputStream dis) throws IOException {
        int length = dis.readInt();
        if (length < 0 || length > MAX_BYTE_ARRAY_SIZE) {
            throw new DatabaseSerializationException("Invalid byte[] length: " + length);
        }
        byte[] bytes = new byte[length];
        dis.readFully(bytes);
        return bytes;
    }

    private static void writeCollection(DataOutputStream dos, Collection<?> col, int depth) throws IOException {
        if (col.size() > MAX_COLLECTION_SIZE) {
            throw new DatabaseSerializationException("Collection size (" + col.size() + ") exceeds maximum limit (" + MAX_COLLECTION_SIZE + ")");
        }
        dos.writeInt(col.size());
        for (Object item : col) {
            writeValue(dos, item, depth);
        }
    }

    private static List<Object> readList(DataInputStream dis, int depth) throws IOException {
        int size = dis.readInt();
        if (size < 0 || size > MAX_COLLECTION_SIZE) {
            throw new DatabaseSerializationException("Invalid list size: " + size);
        }
        List<Object> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(readValue(dis, depth));
        }
        return list;
    }

    private static void writeMap(DataOutputStream dos, Map<?, ?> m, int depth) throws IOException {
        if (m.size() > MAX_COLLECTION_SIZE) {
            throw new DatabaseSerializationException("Map size (" + m.size() + ") exceeds maximum limit (" + MAX_COLLECTION_SIZE + ")");
        }
        dos.writeInt(m.size());
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new DatabaseSerializationException("Nested Map keys must be String instances");
            }
            writeString(dos, key);
            writeValue(dos, entry.getValue(), depth);
        }
    }

    private static Map<String, Object> readMap(DataInputStream dis, int depth) throws IOException {
        int size = dis.readInt();
        if (size < 0 || size > MAX_COLLECTION_SIZE) {
            throw new DatabaseSerializationException("Invalid map size: " + size);
        }
        Map<String, Object> map = new HashMap<>(size);
        for (int i = 0; i < size; i++) {
            String key = readString(dis);
            Object value = readValue(dis, depth);
            map.put(key, value);
        }
        return map;
    }
}
