package kz.rm.files;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

/** Explicit trusted facade. Does not return storage keys or unencrypted bytes. */
@Service
public class AuthenticatedFilesAttachments {
    private final FileAttachmentService service;
    public AuthenticatedFilesAttachments(FileAttachmentService service){this.service=service;}
    public UUID attachCustomer(VerifiedActor actor,UUID tenant,UUID fileId,
                               UUID customerId,String purpose){
        if(actor==null)throw new SecurityException("Verified actor required");
        return service.attachCustomer(actor.userId(),tenant,fileId,customerId,purpose);
    }
    public void detachCustomer(VerifiedActor actor,UUID tenant,UUID attachmentId){
        if(actor==null)throw new SecurityException("Verified actor required");
        service.detachCustomer(actor.userId(),tenant,attachmentId);
    }
    public List<FileAttachmentService.AttachmentSummary> listCustomer(
            VerifiedActor actor,UUID tenant,UUID customerId,int limit) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return service.listCustomer(actor.userId(),tenant,customerId,limit);
    }
}
