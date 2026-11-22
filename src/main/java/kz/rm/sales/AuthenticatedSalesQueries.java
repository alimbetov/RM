package kz.rm.sales;

import kz.rm.iam.VerifiedActor;
import kz.rm.sales.dto.SalesOrderDto;
import kz.rm.sales.dto.SalesOrderDetail;
import java.util.List;
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

    public SalesOrderDetail detail(VerifiedActor actor,UUID tenant,UUID orderId) {
        if(actor==null) throw new SecurityException("Verified actor required");
        return queries.detail(actor.userId(),tenant,orderId);
    }

    public List<SalesOrderDetail.StatusEvent> history(VerifiedActor actor,UUID tenant,
                                                       UUID orderId,int page,int size) {
        if(actor==null) throw new SecurityException("Verified actor required");
        return queries.history(actor.userId(),tenant,orderId,page,size);
    }

    public Page<SalesOrderDto> search(VerifiedActor actor, UUID tenantId,
                                      SalesOrderSearch filters, int page, int size) {
        if (actor == null) throw new SecurityException("Verified actor required");
        return queries.search(actor.userId(), tenantId, filters, page, size);
    }
}
