package kz.rm.files;
import java.util.UUID;
/** Metadata only: binary data lives behind the FILES storage port. */
public record FileObject(UUID tenantId, UUID fileId, long sizeBytes, State state) {
    public enum State { STAGED, AVAILABLE, REJECTED, DELETING, DELETED }
    public FileObject {
        if(tenantId==null || fileId==null || sizeBytes<0 || state==null)
            throw new IllegalArgumentException("Invalid file metadata");
    }
    public boolean downloadable() {return state==State.AVAILABLE;}
}
