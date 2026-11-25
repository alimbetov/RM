package kz.rm.files;

import java.time.Instant;
import java.util.UUID;

/** Metadata only, no storage reference or original filename. */
public record FileAttachmentSummary(UUID attachmentId,UUID fileId,UUID customerId,
                                    String purpose,String mimeType,long sizeBytes,
                                    Instant createdAt) {}
