package kz.rm.sales.dto;

import java.time.Instant;

public record SalesOrderSearch(String status, Instant fromInclusive, Instant toExclusive) {
    public SalesOrderSearch {
        if(status!=null && !status.matches("PLACED|CONFIRMED|PREPARING|READY_FOR_PICKUP|OUT_FOR_DELIVERY|COMPLETED|CANCELLED|REJECTED"))
            throw new IllegalArgumentException("Unknown order status");
        if(fromInclusive!=null && toExclusive!=null && !fromInclusive.isBefore(toExclusive))
            throw new IllegalArgumentException("Invalid order time window");
    }
}
