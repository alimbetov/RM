package kz.rm.intelligence;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public final class CalendarPolicyKey implements Serializable {
    private UUID tenantId;
    private long version;
    public CalendarPolicyKey() {}
    public CalendarPolicyKey(UUID tenantId,long version) {
        this.tenantId=Objects.requireNonNull(tenantId);
        if(version<1) throw new IllegalArgumentException("Positive version required");
        this.version=version;
    }
    @Override public boolean equals(Object o) {
        return o instanceof CalendarPolicyKey k && version==k.version && Objects.equals(tenantId,k.tenantId);
    }
    @Override public int hashCode(){return Objects.hash(tenantId,version);}
}
