package kz.rm.inventory;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.inventory.dto.StockBalanceDto;
import kz.rm.inventory.dto.StockSearch;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class StockBalanceQueryService {
    private final TenantAuthorization authorization;
    private final StockBalanceRepository repository;

    @Transactional(readOnly = true)
    public Page<StockBalanceDto> search(UUID authenticatedActorId, UUID tenantId,
                                        StockSearch search, int page, int size) {
        authorization.requireForCommand(authenticatedActorId,tenantId,DomainPermission.INVENTORY_STOCK_READ);
        if(page<0 || size<1 || size>100)
            throw new IllegalArgumentException("Page must be nonnegative, size 1..100");
        return repository.search(tenantId,search==null?new StockSearch(null,null,false):search,
                PageRequest.of(page,size));
    }
}
