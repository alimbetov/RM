package kz.rm.crm.dto;

import java.util.UUID;

public record ContactSearch(UUID customerId,String status,Boolean primaryOnly) {
    public ContactSearch {
        if(status!=null && !status.matches("ACTIVE|ARCHIVED"))
            throw new IllegalArgumentException("Invalid contact status");
    }
}
