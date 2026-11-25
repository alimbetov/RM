package kz.rm.files;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Fenced uploads: DB claim before IO; no IAM/DB transaction spans stream or scan.
 * Still synthetic-only: real AV and authenticated binary encryption are separate gates.
 */
@Service
public class FileUploadCompletionService {
    private final FileMetadataService metadata;
    private final FileStoragePort storage;
    private final FileScanPort scanner;
    private final JdbcTemplate jdbc;

    FileUploadCompletionService(FileMetadataService metadata,
                                FileStoragePort storage,FileScanPort scanner,
                                JdbcTemplate jdbc) {
        this.metadata=metadata;this.storage=storage;this.scanner=scanner;this.jdbc=jdbc;
    }

    public FileUploadIntent acceptAndComplete(VerifiedActor actor,UUID tenant,UUID fileId,
                                               InputStream binary) throws IOException {
        if(actor==null)throw new SecurityException("Verified actor required");
        var claim=metadata.claim(actor.userId(),tenant,fileId);
        FileUploadIntent draft=claim.intent();
        if(claim.alreadyAvailable()) {
            // The logical state is insufficient: recheck the immutable stored bytes
            // before acknowledging an idempotent retry after process restart.
            var persisted=jdbc.query("""
                SELECT s.storage_key,f.size_bytes,f.sha256
                  FROM crm.file_object f JOIN crm.file_storage s
                    ON s.tenant_id=f.tenant_id AND s.file_id=f.id
                 WHERE f.tenant_id=? AND f.id=? AND s.state='ACTIVE'
                """,rs->rs.next()?new Stored(rs.getString(1),rs.getLong(2),
                        rs.getString(3)):null,tenant,fileId);
            if(persisted==null || !storage.matches(persisted.key(),persisted.size(),persisted.sha())) {
                metadata.quarantineIntegrityFailure(tenant,fileId,
                        persisted==null?"":persisted.key());
                throw new IOException("Persisted file integrity failed");
            }
            return draft;
        }
        UUID token=claim.token();
        FileStoragePort.Staged staged=null;
        String finalKey=null;
        boolean committed=false;
        try {
            if(binary==null)throw new IllegalArgumentException("Binary stream required");
            staged=storage.stage(tenant,fileId,binary,draft.declaredSizeBytes(),draft.mimeType());
            FileScanPort.Verdict verdict=scanner.scan(staged.key());
            if(verdict==FileScanPort.Verdict.INFECTED) {
                metadata.rejectInfected(actor.userId(),tenant,fileId,token);
                throw new IOException("File scan reported infected content");
            }
            if(verdict!=FileScanPort.Verdict.CLEAN)
                throw new IOException("File scanner unavailable or inconclusive");
            finalKey=storage.promote(tenant,fileId,staged);
            if(!storage.matches(finalKey,staged.sizeBytes(),staged.sha256()))
                throw new IOException("Promoted content integrity failed");
            FileUploadIntent result=metadata.finalizeClean(actor.userId(),tenant,fileId,
                    token,staged,finalKey);
            committed=true;
            return result;
        } finally {
            if(!committed) {
                // Process kill may skip this cleanup: durable recovery handles it.
                if(staged!=null)try {storage.delete(staged.key());}catch(IOException ignored){}
                if(finalKey!=null)try {storage.delete(finalKey);}catch(IOException ignored){}
                metadata.release(actor.userId(),tenant,fileId,token);
            }
        }
    }

    private record Stored(String key,long size,String sha){}
}
