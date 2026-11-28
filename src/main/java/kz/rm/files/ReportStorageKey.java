package kz.rm.files;

import kz.rm.security.kms.FileContentCryptoPort.Expected;
import java.io.IOException;
import java.util.UUID;

/** V1 references are deliberately distinct from V0. The attempt is recoverable
 * from the durable storage pointer, never inferred from an untrusted envelope. */
final class ReportStorageKey {
    private ReportStorageKey() {}
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static final String LOCAL_PATTERN = UUID_PATTERN+"/"+UUID_PATTERN+"/(stage|final)-v1-"+UUID_PATTERN;

    static String of(boolean rust, boolean staged, Expected e) {
        return (rust?"rustfs/":"")+e.tenantId()+"/"+e.fileId()+"/"
                +(staged?"stage":"final")+"-v1-"+e.uploadAttempt();
    }
    static boolean encrypted(String key) {
        return key != null && (key.matches(LOCAL_PATTERN) || key.matches("rustfs/"+LOCAL_PATTERN));
    }
    static void require(String key, boolean rust, boolean staged, Expected e) throws IOException {
        if (!of(rust,staged,e).equals(key)) throw new IOException("REPORT_STORAGE_SCOPE");
    }
    static UUID attempt(String key, UUID tenant, UUID file, boolean staged) throws IOException {
        String prefix=(key!=null&&key.startsWith("rustfs/")?"rustfs/":"")+tenant+"/"+file+"/"
                +(staged?"stage":"final")+"-v1-";
        if(!encrypted(key)||!key.startsWith(prefix))throw new IOException("REPORT_STORAGE_SCOPE");
        return UUID.fromString(key.substring(prefix.length()));
    }
    static void rejectLegacyRead(String key) throws IOException {
        if(encrypted(key))throw new IOException("REPORT_STORAGE_REQUIRES_AUTHENTICATED_READ");
    }
}
