package kz.rm.files;

import java.util.UUID;

/** Narrow domain-owned policy; FILES cannot infer object access from tenant grants. */
public interface FileOwnerAccessPort {
    void requireCustomerRead(UUID actor,UUID tenant,UUID customerId);
    void requireCustomerModify(UUID actor,UUID tenant,UUID customerId);
}
