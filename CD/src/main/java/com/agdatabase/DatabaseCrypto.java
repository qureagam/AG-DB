package com.agdatabase;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Handles cryptographic operations for AG Database:
 * <ul>
 *     <li>Key derivation via PBKDF2-HMAC-SHA-256</li>
 *     <li>Authenticated encryption via AES-256-GCM (128-bit authentication tag)</li>
 *     <li>Secure random salt and nonce generation</li>
 * </ul>
 */
public final class DatabaseCrypto {

    public static final String KEY_ALGORITHM = "AES";
    public static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    public static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";

    public static final int KEY_LENGTH_BITS = 256;
    public static final int SALT_LENGTH_BYTES = 16;
    public static final int GCM_NONCE_LENGTH_BYTES = 12;
    public static final int GCM_TAG_LENGTH_BITS = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private DatabaseCrypto() {
    }

    /**
     * Generates a cryptographically secure random salt.
     *
     * @return 16-byte random salt
     */
    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }

    /**
     * Generates a cryptographically secure random nonce (IV) for AES-GCM.
     *
     * @return 12-byte random nonce
     */
    public static byte[] generateNonce() {
        byte[] nonce = new byte[GCM_NONCE_LENGTH_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }

    /**
     * Derives a 256-bit AES SecretKey from a password and salt using PBKDF2-HMAC-SHA-256.
     *
     * @param password   user-provided password as char array
     * @param salt       cryptographic salt
     * @param iterations PBKDF2 iteration count
     * @return 256-bit AES SecretKey
     */
    public static SecretKey deriveKey(char[] password, byte[] salt, int iterations) {
        Objects.requireNonNull(password, "Password must not be null");
        Objects.requireNonNull(salt, "Salt must not be null");
        if (salt.length < SALT_LENGTH_BYTES) {
            throw new IllegalArgumentException("Salt must be at least " + SALT_LENGTH_BYTES + " bytes");
        }
        if (iterations < 1000) {
            throw new IllegalArgumentException("Iterations must be at least 1,000");
        }

        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
            byte[] encoded = factory.generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(encoded, KEY_ALGORITHM);
            } finally {
                // Zero intermediate key bytes
                Arrays.fill(encoded, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new DatabaseCryptoException("Failed to derive encryption key: " + e.getMessage(), e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Encrypts plaintext bytes using AES-256-GCM.
     *
     * @param plaintext raw unencrypted bytes
     * @param key       AES SecretKey
     * @param nonce     12-byte GCM nonce
     * @return ciphertext bytes with appended GCM authentication tag
     */
    public static byte[] encrypt(byte[] plaintext, SecretKey key, byte[] nonce) {
        Objects.requireNonNull(plaintext, "Plaintext must not be null");
        Objects.requireNonNull(key, "SecretKey must not be null");
        Objects.requireNonNull(nonce, "Nonce must not be null");

        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException e) {
            throw new DatabaseCryptoException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts ciphertext bytes using AES-256-GCM and verifies authentication tag.
     *
     * @param ciphertext ciphertext with appended GCM authentication tag
     * @param key        AES SecretKey
     * @param nonce      12-byte GCM nonce
     * @return decrypted plaintext bytes
     * @throws DatabaseCryptoException if decryption or authentication tag verification fails (e.g. wrong password or tampered data)
     */
    public static byte[] decrypt(byte[] ciphertext, SecretKey key, byte[] nonce) {
        Objects.requireNonNull(ciphertext, "Ciphertext must not be null");
        Objects.requireNonNull(key, "SecretKey must not be null");
        Objects.requireNonNull(nonce, "Nonce must not be null");

        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new DatabaseCryptoException("Decryption failed. Incorrect password or tampered database file.", e);
        }
    }

    /**
     * Securely clears a char array in memory.
     *
     * @param chars char array to zero
     */
    public static void wipe(char[] chars) {
        if (chars != null) {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * Securely clears a byte array in memory.
     *
     * @param bytes byte array to zero
     */
    public static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
