package kz.rm.files;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Tenant bound authenticated binary envelope, separate from the string crypto port. */
interface FileContentCryptoPort {
    void encrypt(UUID tenant,UUID fileId,InputStream plaintext,OutputStream encrypted)
            throws IOException;
    void decrypt(UUID tenant,UUID fileId,InputStream encrypted,OutputStream plaintext)
            throws IOException;
}
