package com.agdatabase;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Manages low-level file I/O for AG Database:
 * <ul>
 *     <li>Custom binary file header parsing and writing</li>
 *     <li>Atomic persistence via temporary file swap and {@code FileChannel.force(true)}</li>
 *     <li>Crash resilience and rollback cleanup</li>
 *     <li>Process-level file locking</li>
 * </ul>
 */
public final class DatabaseFile implements AutoCloseable {

    public static final byte[] MAGIC_BYTES = new byte[]{'A', 'G', 'D', 'B'}; // 0x41 0x47 0x44 0x42
    public static final short FORMAT_VERSION = 1;
    public static final String FILE_EXTENSION = ".ag";
    public static final String LOCK_EXTENSION = ".ag.lock";

    private final Path filePath;
    private final Path lockPath;
    private final boolean fileLockingEnabled;

    private FileChannel lockChannel;
    private FileLock fileLock;
    private boolean closed = false;

    /**
     * Represents the encrypted payload and KDF/nonce metadata parsed from an AG file.
     */
    public record EncryptedSnapshot(byte[] salt, int iterations, byte[] nonce, byte[] ciphertext) {
    }

    /**
     * Initializes file manager for a database.
     *
     * @param directory          directory path
     * @param databaseName       database name (with or without .ag extension)
     * @param fileLockingEnabled whether to acquire a file lock
     */
    public DatabaseFile(Path directory, String databaseName, boolean fileLockingEnabled) {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(databaseName, "databaseName must not be null");

        String normalizedName = databaseName.endsWith(FILE_EXTENSION)
                ? databaseName
                : databaseName + FILE_EXTENSION;

        this.filePath = directory.resolve(normalizedName).toAbsolutePath().normalize();
        this.lockPath = directory.resolve(databaseName.endsWith(FILE_EXTENSION)
                ? databaseName.substring(0, databaseName.length() - FILE_EXTENSION.length()) + LOCK_EXTENSION
                : databaseName + LOCK_EXTENSION).toAbsolutePath().normalize();
        this.fileLockingEnabled = fileLockingEnabled;

        if (fileLockingEnabled) {
            acquireLock();
        }
    }

    /**
     * Returns the absolute path of the database file.
     *
     * @return Path
     */
    public Path getFilePath() {
        return filePath;
    }

    /**
     * Checks if the database file currently exists.
     *
     * @return true if exists
     */
    public boolean exists() {
        return Files.exists(filePath);
    }

    /**
     * Reads the encrypted snapshot from the .ag file.
     *
     * @return EncryptedSnapshot or null if file does not exist
     * @throws DatabaseCorruptFileException if the file is invalid or truncated
     */
    public EncryptedSnapshot readSnapshot() {
        if (!exists()) {
            return null;
        }

        try (InputStream in = Files.newInputStream(filePath);
             DataInputStream dis = new DataInputStream(new BufferedInputStream(in))) {

            byte[] magic = new byte[4];
            dis.readFully(magic);
            if (!Arrays.equals(magic, MAGIC_BYTES)) {
                throw new DatabaseCorruptFileException("Invalid file format: magic bytes do not match AG Database signature");
            }

            short version = dis.readShort();
            if (version != FORMAT_VERSION) {
                throw new DatabaseCorruptFileException("Unsupported AG Database format version: " + version + " (supported: " + FORMAT_VERSION + ")");
            }

            int saltLength = dis.readUnsignedByte();
            if (saltLength <= 0 || saltLength > 255) {
                throw new DatabaseCorruptFileException("Corrupt header: invalid salt length " + saltLength);
            }
            byte[] salt = new byte[saltLength];
            dis.readFully(salt);

            int iterations = dis.readInt();
            if (iterations < 1000) {
                throw new DatabaseCorruptFileException("Corrupt header: invalid PBKDF2 iteration count " + iterations);
            }

            int nonceLength = dis.readUnsignedByte();
            if (nonceLength <= 0 || nonceLength > 255) {
                throw new DatabaseCorruptFileException("Corrupt header: invalid nonce length " + nonceLength);
            }
            byte[] nonce = new byte[nonceLength];
            dis.readFully(nonce);

            int ciphertextLength = dis.readInt();
            if (ciphertextLength <= 0) {
                throw new DatabaseCorruptFileException("Corrupt header: invalid ciphertext length " + ciphertextLength);
            }
            byte[] ciphertext = new byte[ciphertextLength];
            dis.readFully(ciphertext);

            return new EncryptedSnapshot(salt, iterations, nonce, ciphertext);
        } catch (EOFException e) {
            throw new DatabaseCorruptFileException("Corrupt database file: premature end of file", e);
        } catch (IOException e) {
            throw new DatabaseException("Failed to read database file: " + filePath, e);
        }
    }

    /**
     * Safely and atomically writes an encrypted snapshot to the database file.
     *
     * @param snapshot the encrypted snapshot to persist
     */
    public synchronized void writeSnapshot(EncryptedSnapshot snapshot) {
        ensureOpen();
        Objects.requireNonNull(snapshot, "Snapshot must not be null");

        Path parent = filePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new DatabaseException("Failed to create directory: " + parent, e);
            }
        }

        // Unique temporary file in the same directory to guarantee atomic rename
        String tempFileName = filePath.getFileName().toString() + ".tmp." + UUID.randomUUID();
        Path tempPath = (parent != null) ? parent.resolve(tempFileName) : Path.of(tempFileName);

        try {
            try (FileChannel channel = FileChannel.open(tempPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {

                // Calculate total buffer size
                // magic(4) + version(2) + saltLen(1) + salt + iter(4) + nonceLen(1) + nonce + cipherLen(4) + ciphertext
                int totalSize = 4 + 2 + 1 + snapshot.salt().length + 4 + 1 + snapshot.nonce().length + 4 + snapshot.ciphertext().length;
                ByteBuffer buffer = ByteBuffer.allocate(totalSize);

                buffer.put(MAGIC_BYTES);
                buffer.putShort(FORMAT_VERSION);
                buffer.put((byte) snapshot.salt().length);
                buffer.put(snapshot.salt());
                buffer.putInt(snapshot.iterations());
                buffer.put((byte) snapshot.nonce().length);
                buffer.put(snapshot.nonce());
                buffer.putInt(snapshot.ciphertext().length);
                buffer.put(snapshot.ciphertext());

                buffer.flip();
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }

                // Force data and metadata to disk
                channel.force(true);
            }

            // Atomic move / replace
            try {
                Files.move(tempPath, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // Fallback for filesystems that do not support ATOMIC_MOVE
                Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Cleanup temp file on failure
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException ignored) {
            }
            throw new DatabaseException("Failed to atomically save database to " + filePath, e);
        }
    }

    private void acquireLock() {
        try {
            Path parent = lockPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE);

            try {
                fileLock = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                fileLock = null;
            }

            if (fileLock == null) {
                closeLockResources();
                throw new DatabaseLockedException("Database file is locked by another process or instance: " + filePath);
            }
        } catch (IOException e) {
            closeLockResources();
            throw new DatabaseException("Failed to acquire database lock for " + filePath, e);
        }
    }

    private void closeLockResources() {
        if (fileLock != null) {
            try {
                fileLock.release();
            } catch (IOException ignored) {
            }
            fileLock = null;
        }
        if (lockChannel != null) {
            try {
                lockChannel.close();
            } catch (IOException ignored) {
            }
            lockChannel = null;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new DatabaseClosedException("Database file manager is closed");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeLockResources();
        if (fileLockingEnabled && Files.exists(lockPath)) {
            try {
                Files.deleteIfExists(lockPath);
            } catch (IOException ignored) {
                // Ignore failure to delete lock file on platforms where it might still be held
            }
        }
    }
}
