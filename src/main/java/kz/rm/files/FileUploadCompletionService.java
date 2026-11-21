package kz.rm.files;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * No outer transaction spans binary streaming or AV.
 * All data is synthetic until qualified encryption and real scanner gates.
 */
@Service
public class FileUploadCompletionService {
    private final FileMetadataService metadata;
    private final FileStoragePort storage;
    private final FileScanPort scanner;

    FileUploadCompletionService(FileMetadataService metadata,
                                FileStoragePort storage,FileScanPort scanner) {
        this.metadata=metadata;this.storage=storage;this.scanner=scanner;
    }

    public FileUploadIntent acceptAndComplete(VerifiedActor actor,UUID tenant,UUID fileId,
                                               InputStream binary) throws IOException {
        if(actor==null)throw new SecurityException("Verified actor required");
        FileUploadIntent draft=metadata.requireUpload(actor.userId(),tenant,fileId);
        if("AVAILABLE".equals(draft.state()) && "CLEAN".equals(draft.scanStatus()))
            return draft; // Exact intent already finalized: no overwrite.
        if(!"STAGED".equals(draft.state()) || !"NOT_SCANNED".equals(draft.scanStatus()))
            throw new IllegalStateException("Upload cannot be finalized in current state");
        if(binary==null)throw new IllegalArgumentException("Binary stream required");
        FileStoragePort.Staged staged=storage.stage(tenant,fileId,binary,
                draft.declaredSizeBytes(),draft.mimeType());
        String finalKey=null;
        boolean committed=false;
        try {
            FileScanPort.Verdict verdict=scanner.scan(staged.key());
            if(verdict==FileScanPort.Verdict.INFECTED) {
                metadata.rejectInfected(actor.userId(),tenant,fileId);
                throw new IOException("File scan reported infected content");
            }
            if(verdict!=FileScanPort.Verdict.CLEAN)
                throw new IOException("File scanner unavailable or result inconclusive");
            finalKey=storage.promote(tenant,fileId,staged);
            if(!storage.exists(finalKey))
                throw new IOException("Promoted content is missing");
            FileUploadIntent result=metadata.finalizeClean(actor.userId(),tenant,fileId,
                    staged,finalKey);
            committed=true;
            return result;
        } finally {
            if(!committed) {
                // Best effort orphan cleanup. An unreferenced blob is never AVAILABLE;
                // R1.0C adds durable reconciler and fault recovery evidence.
                try {storage.delete(staged.key());}catch(IOException ignored){}
                if(finalKey!=null)try {storage.delete(finalKey);}catch(IOException ignored){}
            }
        }
    }
}
