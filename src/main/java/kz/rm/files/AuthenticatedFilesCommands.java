package kz.rm.files;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** Trusted FILES R1.0A application boundary: synthetic metadata only. */
@Service
public class AuthenticatedFilesCommands {
    private final FileMetadataService service;
    public AuthenticatedFilesCommands(FileMetadataService service){this.service=service;}
    public FileUploadIntent begin(VerifiedActor actor,UUID tenant,String mime,
                                  long declaredSize,String requestKey){
        if(actor==null)throw new SecurityException("Verified actor required");
        return service.begin(actor.userId(),tenant,mime,declaredSize,requestKey);
    }
}
