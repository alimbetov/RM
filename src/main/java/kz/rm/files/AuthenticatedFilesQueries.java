package kz.rm.files;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Limited own-draft metadata; does not expose file bytes or cross-owner listing. */
@Service
public class AuthenticatedFilesQueries {
    private final FileMetadataService service;
    public AuthenticatedFilesQueries(FileMetadataService service){this.service=service;}
    public FileUploadIntent ownStaged(VerifiedActor actor,UUID tenant,UUID fileId){
        if(actor==null)throw new SecurityException("Verified actor required");
        return service.ownStaged(actor.userId(),tenant,fileId);
    }
}
