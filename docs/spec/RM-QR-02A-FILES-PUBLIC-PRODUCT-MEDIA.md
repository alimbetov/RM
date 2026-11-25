# RM-QR-02A — FILES CATALOG_ITEM Public Product Media, service-first

**Parent:** RM-QR-R1.4 canonical, QR-02A.
**Branch:** base_dev; no new controllers and no changes to SALES/INVENTORY.
**Status:** candidate implementation, not transport/production admission.

## Contract

1. Source remains a private INTERNAL FILES object created by existing AuthenticatedFilesCommands / FileUploadCompletionService and scanned CLEAN by the configured FileScanPort. If the scanner is unavailable, do not publish.
2. Verified seller must have both FILES_ATTACH and CATALOG_WRITE. Source uploaded_by must equal this seller actor; it cannot already have any active FILES attachment (especially CRM_CUSTOMER).
3. Decode JPEG or PNG with ImageIO after persisted checksum validation and 20-MiB bound; enforce max 4096x4096 and 4 million pixels. Raster is copied to a new BufferedImage and encoded as PNG: no EXIF/GPS or arbitrary original metadata is retained. Do not publish source bytes.
4. The derived PNG is uploaded *again* through the existing FILES upload/scan pipeline (clean verdict required), as a new private INTERNAL file, then linked to CATALOG_ITEM through a typed FILES attachment and seller-approved variant. No DB transaction spans binary IO, image decoding or ClamAV.
5. After IO, re-admit IAM permissions, lock PRODUCT and FILES rows, recheck clean source/derivative, unique request key, image slot, and attach+approve atomically. R1 at most 20 images per catalog item and one active image per slot. A cross-stage failed approval may leave a private unreferenced derivative, but cannot expose it.
6. Public media metadata is returned only when seller tenant ACTIVE, store and offer PUBLISHED, PRODUCT PUBLISHED, alias ACTIVE, attachment not detached, variant ACTIVE and source/derivative AVAILABLE+CLEAN. The safe public response contains only opaque mediaRef, sanitized MIME, raster dimensions and sort order; no internal tenant/file ID, raw storage key or signed blob URL.
7. Public binary copy uses a separate FILES-owned trusted service method and rechecks the same admission predicate before storage checks/stream. Source bytes never go through public streaming. A revocation committed before a new read denies that read; an already admitted in-flight stream may complete.
8. Staff revoke requires FILES_DETACH + CATALOG_WRITE, detaches typed attachment and clears active slot in one transaction. Source file and sanitized derivative are retained for audit/recovery, not made public by default.
9. Public card composes approved images from FILES as a separate bounded, freshly authorized read. QR product offer ref remains the only entry to the public card. Default FILES implementation still denies uploads without qualified AV.

## State, permissions and keys

- Owner: FILES; tenant composite keys; product owner identified by CATALOG immutable catalogItem ID.
- Original/derived file: FILES existing authoritative objects; no duplicate blob store.
- Approval: crm.file_catalog_public_variant state ACTIVE/REVOKED, unique public_ref, unique (tenant,request_key), nullable active_slot+unique (tenant,item,active_slot), source/derivative/attachment composite tenant FKs.
- Seller approved image publication is explicit boolean from authenticated service caller; future UI must require user confirmation, not infer consent from upload.
- Sanitizer format v1: PNG_R1; JPEG and PNG inputs only, even if general FILES supports WebP/PDF.
- Unknown/invalid/revoked media URL: generic PUBLIC_MEDIA_UNAVAILABLE; no private original.
- Rate limits, CDN/revocation TTL, public HTTPS controller, real scanner/phone and legal content review remain separately gated.

## Test matrix

- Synthetic CLEAN local FILES test: sanitized PNG bytes only, private source remains private, typed product attachment and card gallery.
- Negative: missing seller approval, CRM_CUSTOMER attachment, foreign tenant, unknown/ERROR scan, hidden offer, revoked media, request key conflict and slot collision.
- Existing FILES/CRM permissions, KMS, H2 and real PostgreSQL workflow must not regress.
- Real RustFS + real ClamAV infrastructure, populated PostgreSQL migration, EXIF re-encode checks and exact SHA qualification must be recorded before full release. Synthetic scanner is NOT real AV proof.

**Limitations:** No public transport, binary URL adapter, delivery photo, SERVICE media booking, paid badge or product search; those belong to later gates. Production remains HOLD.
