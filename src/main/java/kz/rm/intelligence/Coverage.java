package kz.rm.intelligence;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Requested interval is not evidence of historical source completeness. */
public record Coverage(Instant requestedFrom,Instant requestedUntil,boolean complete,List<String> reasonCodes) {
    public Coverage {
        reasonCodes=List.copyOf(reasonCodes);
        if((requestedFrom==null)!=(requestedUntil==null) ||
            (requestedFrom!=null&&!requestedFrom.isBefore(requestedUntil)))
            throw new IllegalArgumentException("Invalid coverage interval");
        if(complete&&!reasonCodes.isEmpty())throw new IllegalArgumentException("Complete coverage cannot have gaps");
    }
}
