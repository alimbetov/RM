package kz.rm.sales;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.sales.dto.SalesOrderDto;
import kz.rm.sales.dto.SalesOrderDetail;
import java.util.List;
import kz.rm.sales.dto.SalesOrderSearch;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Internal seller order read service; actor identity must come from trusted principal. */
@Service
@RequiredArgsConstructor
class SalesOrderQueryService {
    private final TenantAuthorization authorization;
    private final SalesOrderRepository orders;

    @Transactional
    public SalesOrderDetail detail(UUID user,UUID tenant,UUID orderId) {
        authorization.requireForCommand(user,tenant,DomainPermission.SALES_ORDER_READ);
        if(orderId==null) throw new IllegalArgumentException("Order required");
        return orders.detail(tenant,orderId);
    }

    @Transactional
    public List<SalesOrderDetail.StatusEvent> history(UUID user,UUID tenant,
                                                       UUID orderId,int page,int size) {
        authorization.requireForCommand(user,tenant,DomainPermission.SALES_ORDER_READ);
        if(orderId==null||page<0||size<1||size>100)
            throw new IllegalArgumentException("Invalid history page");
        orders.detail(tenant,orderId); // reject missing or cross-tenant order
        return orders.history(tenant,orderId,page,size);
    }

    @Transactional // shared IAM row locks require a writable PostgreSQL transaction
    public Page<SalesOrderDto> search(UUID authenticatedActorId,UUID tenantId,
                                      SalesOrderSearch filters,int page,int size) {
        authorization.requireForCommand(authenticatedActorId,tenantId,DomainPermission.SALES_ORDER_READ);
        if(page<0 || size<1 || size>100)
            throw new IllegalArgumentException("Page must be nonnegative, size 1..100");
        return orders.search(tenantId,
                filters==null?new SalesOrderSearch(null,null,null):filters,PageRequest.of(page,size));
    }
}
