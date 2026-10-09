# AG Database (`.ag`)

A lightweight, self-contained, encrypted key-value database engine in Java using `HashMap<String, Object>` as its core in-memory storage and custom `.ag` encrypted binary files for disk persistence.

---

## 1. Project Directory Structure

```text
d:/Java/CD
├── pom.xml
├── README.md
├── src
│   ├── main
│   │   └── java
│   │       ├── com
│   │       │   ├── agdatabase
│   │       │   │   ├── Database.java
│   │       │   │   ├── DatabaseConfig.java
│   │       │   │   ├── DatabaseCrypto.java
│   │       │   │   ├── DatabaseFile.java
│   │       │   │   ├── DatabaseSerializer.java
│   │       │   │   ├── DatabaseValidator.java
│   │       │   │   ├── DatabaseException.java
│   │       │   │   ├── DatabaseCryptoException.java
│   │       │   │   ├── DatabaseCorruptFileException.java
│   │       │   │   ├── DatabaseDuplicateKeyException.java
│   │       │   │   ├── DatabaseKeyNotFoundException.java
│   │       │   │   ├── DatabaseSerializationException.java
│   │       │   │   ├── DatabaseLockedException.java
│   │       │   │   └── DatabaseClosedException.java
│   │       │   └── database
│   │       │       └── Main.java
│   └── test
│       └── java
│           └── com
│               └── agdatabase
│                   └── DatabaseTest.java
```

---

## 2. Core Architecture & Design

AG Database is designed as a direct persistent replacement for an in-memory `HashMap`:

- **In-Memory Core**: Operations run directly against a Java `HashMap<String, Object>` with synchronized thread safety.
- **Custom Binary File Format**: Stored as `.ag` files with a documented binary header, cryptographic salt, nonce, and AES-256-GCM authenticated ciphertext.
- **Safe Persistence**: Writes to a temporary file (`.tmp.<uuid>`), issues `FileChannel.force(true)`, and replaces the target file atomically via `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`.
- **Zero Native Java Deserialization**: Uses an explicit type-tagged binary serialization protocol (`DatabaseSerializer`) with length checks and nesting depth limits.
- **Mutual Exclusion**: Employs an OS-level file lock (`.ag.lock`) to prevent concurrent process conflicts.

---

## 3. Custom `.ag` File Format Specification

The `.ag` file layout is binary (big-endian):

| Offset (Bytes) | Field | Type | Description |
|---|---|---|---|
| 0 .. 3 | Magic Bytes | 4 bytes | `0x41 0x47 0x44 0x42` (ASCII `"AGDB"`) |
| 4 .. 5 | Format Version | Short (2 bytes) | `0x0001` (Version 1) |
| 6 | Salt Length | Byte (1 byte) | Length of cryptographic salt (16 bytes) |
| 7 .. 22 | Salt | 16 bytes | Cryptographically secure random PBKDF2 salt |
| 23 .. 26 | Iterations | Int (4 bytes) | PBKDF2 iteration count (default: 65,536) |
| 27 | Nonce Length | Byte (1 byte) | Length of GCM IV/nonce (12 bytes) |
| 28 .. 39 | Nonce / IV | 12 bytes | Fresh random 96-bit AES-GCM nonce |
| 40 .. 43 | Payload Length | Int (4 bytes) | Length of ciphertext + authentication tag |
| 44 .. End | Ciphertext + Tag | Variable | AES-256-GCM ciphertext + 128-bit authentication tag |

---

## 4. Encryption & Password Handling

- **Algorithm**: `AES/GCM/NoPadding` (256-bit key, 128-bit authentication tag).
- **Key Derivation Function (KDF)**: `PBKDF2WithHmacSHA256` using 65,536 iterations (configurable) and 16-byte random salts.
- **Salt & Nonce Hygiene**: Every `save()` operation generates a fresh cryptographically secure random salt and 12-byte GCM nonce via `SecureRandom`.
- **No Stored Keys**: Passwords and derived secret keys are never stored on disk.
- **Memory Security**: Passwords can be supplied as `char[]` and are automatically wiped from memory (`\0`) when the database is closed.
- **Integrity Verification**: Corrupted ciphertext, bit-flips, or wrong passwords cause AES-GCM authentication verification to fail, throwing a descriptive `DatabaseCryptoException`.

---

## 5. Supported Data Types & Serialization Rules

AG Database serializes data using 1-byte type tags without reflection:

| Tag Byte | Type | Encoding Format |
|---|---|---|
| `0x00` | `null` | Tag byte only |
| `0x01` | `String` | 4-byte byte length + UTF-8 bytes (up to 64 MB) |
| `0x02` | `Integer` | 4-byte big-endian int |
| `0x03` | `Long` | 8-byte big-endian long |
| `0x04` | `Double` | 8-byte IEEE 754 double |
| `0x05` | `Float` | 4-byte IEEE 754 float |
| `0x06` | `Boolean` | 1 byte (`0x01` = true, `0x00` = false) |
| `0x07` | `Short` | 2-byte big-endian short |
| `0x08` | `Byte` | 1-byte signed byte |
| `0x09` | `Character` | 2-byte UTF-16 character |
| `0x0A` | `byte[]` | 4-byte length + raw bytes |
| `0x0B` | `List<?>` | 4-byte count + sequence of tagged elements |
| `0x0C` | `Map<String, ?>` | 4-byte count + pairs of string keys and tagged values |

*Note: Nested collections (`List`, `Map`) enforce a maximum recursion depth of 32 levels to prevent stack overflow.* Unsupported arbitrary objects immediately throw `DatabaseSerializationException`.

---

## 6. API Usage Guide

### Basic Usage

```java
import com.agdatabase.Database;

try (Database db = new Database("game", "StrongPassword123!")) {
    // Create
    db.add("playerName", "Alex");
    db.add("coins", 500);
    db.add("level", 4);

    // Read
    System.out.println(db.get("playerName")); // Alex
    System.out.println(db.contains("coins"));  // true

    // Update
    db.update("coins", 750);

    // Delete
    db.delete("level");

    // Explicit persist
    db.save();
}
```

### Try-With-Resources & Auto-Close

```java
// Reopening the database
try (Database db = new Database("game", "StrongPassword123!")) {
    System.out.println(db.get("playerName")); // Alex
    System.out.println(db.get("coins"));      // 750
    System.out.println(db.get("level"));      // null
}
```

### Custom Configuration (`DatabaseConfig`)

```java
DatabaseConfig config = DatabaseConfig.builder()
        .directory("C:/data/databases")
        .autoSave(true)                    // Automatically save on every write
        .pbkdf2Iterations(100_000)         // Higher security iterations
        .fileLocking(true)                 // Enable OS file locking
        .wipePasswordOnClose(true)         // Wipe char[] password in memory
        .build();

try (Database db = new Database("settings", "MasterKey", config)) {
    db.put("theme", "dark"); // Auto-persists immediately
}
```

---

## 7. Error Handling & Exception Hierarchy

All database exceptions inherit from `DatabaseException`:

- **`DatabaseCryptoException`**: Thrown when decryption fails due to an incorrect password or tampered ciphertext.
- **`DatabaseDuplicateKeyException`**: Thrown by `add(key, value)` when the key is already present.
- **`DatabaseKeyNotFoundException`**: Thrown by `update(key, value)` when the key does not exist.
- **`DatabaseCorruptFileException`**: Thrown when header bytes, magic bytes, or file length are invalid.
- **`DatabaseSerializationException`**: Thrown when serializing an unsupported type or exceeding limits.
- **`DatabaseLockedException`**: Thrown when another process or instance holds the `.ag.lock` file.
- **`DatabaseClosedException`**: Thrown when invoking operations on a closed `Database` instance.

---

## 8. Build, Test, and Run Commands

### Requirements
- Java 17 or higher
- Apache Maven 3.8+

### Compile & Test
```powershell
mvn clean test
```

### Run Demonstration
```powershell
mvn compile exec:java "-Dexec.mainClass=com.database.Main"
```

---

## 9. Known Limitations

1. **In-Memory Scale**: The entire key-value dataset resides in JVM memory (`HashMap`). Datasets should fit within available RAM.
2. **Single-Writer Exclusive Lock**: Process-level mutual exclusion prevents multiple processes from modifying the same database concurrently.
3. **Key Format**: All database keys must be non-null `String` values. Key names like `"user.name"` are treated as literal flat strings, not nested objects.
