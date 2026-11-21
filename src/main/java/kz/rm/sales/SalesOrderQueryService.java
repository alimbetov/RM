package kz.rm.sales;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.sales.dto.SalesOrderDto;
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

    @Transactional(readOnly=true)
    public Page<SalesOrderDto> search(UUID authenticatedActorId,UUID tenantId,
                                      SalesOrderSearch filters,int page,int size) {
        authorization.require(authenticatedActorId,tenantId,DomainPermission.SALES_ORDER_READ);
        if(page<0 || size<1 || size>100)
            throw new IllegalArgumentException("Page must be nonnegative, size 1..100");
        return orders.search(tenantId,
                filters==null?new SalesOrderSearch(null,null,null):filters,PageRequest.of(page,size));
    }
}
