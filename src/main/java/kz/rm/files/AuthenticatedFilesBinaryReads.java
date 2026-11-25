package kz.rm.files;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Trusted service-only binary read. Never gives out filesystem key or plaintext
 * byte[]; checks current owner permissions and authenticated plaintext content.
 */
@Service
public class AuthenticatedFilesBinaryReads {
    private final FileAttachmentService attachments;
    private final FileStoragePort storage;
    private final FileMetadataService metadata;

    AuthenticatedFilesBinaryReads(FileAttachmentService attachments,
                                  FileStoragePort storage,FileMetadataService metadata){
        this.attachments=attachments;this.storage=storage;this.metadata=metadata;
    }
    public long copyCustomerAttachment(VerifiedActor actor,UUID tenant,
                                       UUID attachmentId,OutputStream sink) throws IOException {
        if(actor==null)throw new SecurityException("Verified actor required");
        if(sink==null)throw new IllegalArgumentException("Download sink required");
        var ref=attachments.authorizeCustomerBinaryRead(actor.userId(),tenant,attachmentId);
        if(!storage.matches(ref.key(),ref.size(),ref.sha256())) {
            metadata.quarantineIntegrityFailure(tenant,ref.fileId(),ref.key());
            throw new IOException("File integrity verification failed");
        }
        storage.streamVerified(tenant,ref.fileId(),ref.key(),sink);
        return ref.size();
    }
}
