package kz.rm.security.kms;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** One company row and three reusable storage slots; generations never repeat. */
@Entity
@Table(name = "kms_tenant_key", schema = "security")
public class TenantKeyEntity {
    @Id
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "key_a") private byte[] keyA;
    @Column(name = "generation_a") private Long generationA;
    @Enumerated(EnumType.STRING)
    @Column(name = "status_a", nullable = false, length = 20)
    private KeyStatus statusA = KeyStatus.EMPTY;
    @Column(name = "remaining_a", nullable = false) private long remainingA;
    @Column(name = "migrated_a", nullable = false) private long migratedA;
    @Column(name = "last_scan_a") private Instant lastScanA;
    @Column(name = "wrapping_key_id_a", length = 128) private String wrappingKeyIdA;

    @Column(name = "key_b") private byte[] keyB;
    @Column(name = "generation_b") private Long generationB;
    @Enumerated(EnumType.STRING)
    @Column(name = "status_b", nullable = false, length = 20)
    private KeyStatus statusB = KeyStatus.EMPTY;
    @Column(name = "remaining_b", nullable = false) private long remainingB;
    @Column(name = "migrated_b", nullable = false) private long migratedB;
    @Column(name = "last_scan_b") private Instant lastScanB;
    @Column(name = "wrapping_key_id_b", length = 128) private String wrappingKeyIdB;

    @Column(name = "key_c") private byte[] keyC;
    @Column(name = "generation_c") private Long generationC;
    @Enumerated(EnumType.STRING)
    @Column(name = "status_c", nullable = false, length = 20)
    private KeyStatus statusC = KeyStatus.EMPTY;
    @Column(name = "remaining_c", nullable = false) private long remainingC;
    @Column(name = "migrated_c", nullable = false) private long migratedC;
    @Column(name = "last_scan_c") private Instant lastScanC;
    @Column(name = "wrapping_key_id_c", length = 128) private String wrappingKeyIdC;

    @Column(name = "active_slot", nullable = false, length = 1) private String activeSlot;
    @Column(name = "ready_slot", length = 1) private String readySlot;
    @Column(name = "next_generation", nullable = false) private long nextGeneration;
    @Column(name = "rotate_after", nullable = false) private Instant rotateAfter;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    protected TenantKeyEntity() {}

    public static TenantKeyEntity first(UUID tenantId, byte[] wrapped,
            String wrappingKeyId, Instant now) {
        TenantKeyEntity entity = new TenantKeyEntity();
        entity.tenantId = tenantId;
        entity.keyA = wrapped.clone();
        entity.generationA = 1L;
        entity.wrappingKeyIdA = wrappingKeyId;
        entity.statusA = KeyStatus.ACTIVE;
        entity.activeSlot = "A";
        entity.nextGeneration = 2;
        entity.rotateAfter = now.plusSeconds(90L * 24 * 3600);
        entity.updatedAt = now;
        return entity;
    }

    public UUID getTenantId() { return tenantId; }
    public String getActiveSlot() { return activeSlot; }
    public long getNextGeneration() { return nextGeneration; }

    public long activeGeneration() {
        Slot slot = active();
        if (slot.status() != KeyStatus.ACTIVE) {
            throw new IllegalStateException("Invalid ACTIVE slot state");
        }
        return slot.generation();
    }

    public Slot active() {
        if (activeSlot == null) throw new IllegalStateException("No ACTIVE slot");
        Slot slot = switch (activeSlot) {
            case "A" -> new Slot(generationA, statusA, keyA, wrappingKeyIdA);
            case "B" -> new Slot(generationB, statusB, keyB, wrappingKeyIdB);
            case "C" -> new Slot(generationC, statusC, keyC, wrappingKeyIdC);
            default -> throw new IllegalStateException("Invalid active slot");
        };
        if (slot.status() != KeyStatus.ACTIVE || slot.generation() == null) {
            throw new IllegalStateException("No valid ACTIVE key");
        }
        return slot;
    }

    public Slot forGeneration(long generation) {
        if (generation < 1) throw new IllegalArgumentException("Invalid key generation");
        if (generationA != null && generationA == generation)
            return new Slot(generationA, statusA, keyA, wrappingKeyIdA);
        if (generationB != null && generationB == generation)
            return new Slot(generationB, statusB, keyB, wrappingKeyIdB);
        if (generationC != null && generationC == generation)
            return new Slot(generationC, statusC, keyC, wrappingKeyIdC);
        throw new IllegalStateException("Key generation not available");
    }

    public record Slot(Long generation, KeyStatus status, byte[] wrappedKey, String wrappingKeyId) {
        public Slot {
            wrappedKey = wrappedKey == null ? null : wrappedKey.clone();
        }
        @Override public byte[] wrappedKey() {
            return wrappedKey == null ? null : wrappedKey.clone();
        }
    }
}
