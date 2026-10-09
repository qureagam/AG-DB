package com.agdatabase;

/**
 * Thrown when cryptographic operations fail, such as wrong password,
 * corrupted authentication tag, or decryption errors.
 */
public class DatabaseCryptoException extends DatabaseException {

    public DatabaseCryptoException(String message) {
        super(message);
    }

    public DatabaseCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
