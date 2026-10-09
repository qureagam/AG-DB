package com.agdatabase;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration options for AG Database instances.
 */
public final class DatabaseConfig {

    public static final int DEFAULT_PBKDF2_ITERATIONS = 65_536;
    public static final boolean DEFAULT_AUTO_SAVE = false;
    public static final boolean DEFAULT_FILE_LOCKING = true;
    public static final boolean DEFAULT_WIPE_PASSWORD_ON_CLOSE = true;

    private final Path directory;
    private final boolean autoSave;
    private final int pbkdf2Iterations;
    private final boolean fileLocking;
    private final boolean wipePasswordOnClose;

    private DatabaseConfig(Builder builder) {
        this.directory = builder.directory;
        this.autoSave = builder.autoSave;
        this.pbkdf2Iterations = builder.pbkdf2Iterations;
        this.fileLocking = builder.fileLocking;
        this.wipePasswordOnClose = builder.wipePasswordOnClose;
    }

    public static DatabaseConfig defaultConfig() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Path getDirectory() {
        return directory;
    }

    public boolean isAutoSave() {
        return autoSave;
    }

    public int getPbkdf2Iterations() {
        return pbkdf2Iterations;
    }

    public boolean isFileLocking() {
        return fileLocking;
    }

    public boolean isWipePasswordOnClose() {
        return wipePasswordOnClose;
    }

    public static final class Builder {
        private Path directory = Path.of(".");
        private boolean autoSave = DEFAULT_AUTO_SAVE;
        private int pbkdf2Iterations = DEFAULT_PBKDF2_ITERATIONS;
        private boolean fileLocking = DEFAULT_FILE_LOCKING;
        private boolean wipePasswordOnClose = DEFAULT_WIPE_PASSWORD_ON_CLOSE;

        public Builder directory(Path directory) {
            this.directory = Objects.requireNonNull(directory, "directory must not be null");
            return this;
        }

        public Builder directory(String directoryPath) {
            Objects.requireNonNull(directoryPath, "directoryPath must not be null");
            this.directory = Path.of(directoryPath);
            return this;
        }

        public Builder autoSave(boolean autoSave) {
            this.autoSave = autoSave;
            return this;
        }

        public Builder pbkdf2Iterations(int iterations) {
            if (iterations < 1000) {
                throw new IllegalArgumentException("pbkdf2Iterations must be at least 1,000 for security");
            }
            this.pbkdf2Iterations = iterations;
            return this;
        }

        public Builder fileLocking(boolean fileLocking) {
            this.fileLocking = fileLocking;
            return this;
        }

        public Builder wipePasswordOnClose(boolean wipePasswordOnClose) {
            this.wipePasswordOnClose = wipePasswordOnClose;
            return this;
        }

        public DatabaseConfig build() {
            return new DatabaseConfig(this);
        }
    }
}
