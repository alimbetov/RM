package kz.rm.files;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Internal binary storage, never a transport or public URL capability. */
interface FileStoragePort {
    record Staged(String key,long sizeBytes,String sha256,String detectedMime) {}
    Staged stage(UUID tenant,UUID fileId,InputStream bytes,long declaredSize,String mime) throws IOException;
    String promote(UUID tenant,UUID fileId,Staged staged) throws IOException;
    boolean exists(String key) throws IOException;
    void delete(String key) throws IOException;
}
