package kz.rm.files;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Internal binary storage, never a transport or public URL capability. */
interface FileStoragePort {
    record Staged(String key,long sizeBytes,String sha256,String detectedMime,int cryptoVersion) {}
    Staged stage(UUID tenant,UUID fileId,InputStream bytes,long declaredSize,String mime) throws IOException;
    String promote(UUID tenant,UUID fileId,Staged staged) throws IOException;
    /** Scanner-only private staging stream; not a user download interface. */
    InputStream openStagedForScan(String stagingKey) throws IOException;
    boolean exists(String key) throws IOException;
    /** Verify checksum and length on persisted bytes, not merely path existence. */
    boolean matches(String key,long expectedSize,String sha256) throws IOException;
    /** Bounded file-scoped enumeration used only by trusted recovery logic. */
    java.util.List<String> listKeys(UUID tenant,UUID fileId) throws IOException;
    /** Stream bytes from a private final blob after caller's tenant/owner/hash checks. */
    void streamVerified(UUID tenant,UUID fileId,String key,java.io.OutputStream sink) throws IOException;
    void delete(String key) throws IOException;
}
