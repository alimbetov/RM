package kz.rm.security.kms;

/**
 * Framework-free application port. Future transport adapters must authenticate
 * callers and construct CryptoContext from authoritative tenant/object data.
 * Implementations must never expose raw tenant DEKs over a service boundary.
 */
public interface TenantCryptoPort {
    EncryptedValue encrypt(CryptoContext context, String plaintext);
    String decrypt(CryptoContext context, EncryptedValue encrypted);
}
