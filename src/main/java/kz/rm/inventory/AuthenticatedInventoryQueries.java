package kz.rm.inventory;

import kz.rm.iam.VerifiedActor;
import kz.rm.inventory.dto.StockBalanceDto;
import kz.rm.inventory.dto.StockSearch;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Trusted actor boundary for tenant-isolated stock read models. */
@Service
public class AuthenticatedInventoryQueries {
    private final StockBalanceQueryService queries;

    public AuthenticatedInventoryQueries(StockBalanceQueryService queries) {
        this.queries = queries;
    }

    public Page<StockBalanceDto> search(VerifiedActor actor, UUID tenantId,
                                        StockSearch filter, int page, int size) {
        if (actor == null) throw new SecurityException("Verified actor required");
        return queries.search(actor.userId(), tenantId, filter, page, size);
    }
}
