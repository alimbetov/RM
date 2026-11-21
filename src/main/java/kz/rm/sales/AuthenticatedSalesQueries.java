package kz.rm.sales;

import kz.rm.iam.VerifiedActor;
import kz.rm.sales.dto.SalesOrderDto;
import kz.rm.sales.dto.SalesOrderSearch;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Trusted actor boundary for tenant-isolated seller order searches. */
@Service
public class AuthenticatedSalesQueries {
    private final SalesOrderQueryService queries;

    public AuthenticatedSalesQueries(SalesOrderQueryService queries) {
        this.queries = queries;
    }

    public Page<SalesOrderDto> search(VerifiedActor actor, UUID tenantId,
                                      SalesOrderSearch filters, int page, int size) {
        if (actor == null) throw new SecurityException("Verified actor required");
        return queries.search(actor.userId(), tenantId, filters, page, size);
    }
}
