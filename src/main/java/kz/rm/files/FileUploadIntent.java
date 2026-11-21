package kz.rm.files;

import java.util.UUID;

/** Synthetic-only R1.0A metadata; never a usable storage/download capability. */
public record FileUploadIntent(UUID fileId,String state,String scanStatus,
                               String mimeType,long declaredSizeBytes) {}
