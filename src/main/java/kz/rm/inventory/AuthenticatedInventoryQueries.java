package kz.rm.inventory;

import kz.rm.iam.VerifiedActor;
import kz.rm.inventory.dto.StockBalanceDto;
import kz.rm.inventory.dto.InventoryMovementSearch;
import kz.rm.inventory.dto.InventoryMovementDto;
import kz.rm.inventory.dto.InventoryReservationEventDto;
import kz.rm.inventory.dto.InventoryStocktakeDto;
import kz.rm.inventory.dto.StockSearch;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Trusted actor boundary for tenant-isolated stock read models. */
@Service
public class AuthenticatedInventoryQueries {
    private final StockBalanceQueryService queries;
    private final InventoryHistoryQueryService history;

    public AuthenticatedInventoryQueries(StockBalanceQueryService queries,
                                          InventoryHistoryQueryService history) {
        this.queries = queries;
        this.history = history;
    }

    public Page<StockBalanceDto> search(VerifiedActor actor, UUID tenantId,
                                        StockSearch filter, int page, int size) {
        if (actor == null) throw new SecurityException("Verified actor required");
        return queries.search(actor.userId(), tenantId, filter, page, size);
    }

    public StockBalanceDto getPosition(VerifiedActor actor,UUID tenant,UUID location,UUID item){
        if(actor==null)throw new SecurityException("Verified actor required");
        return history.getPosition(actor.userId(),tenant,location,item);
    }

    public Page<InventoryMovementDto> searchMovements(
            VerifiedActor actor,UUID tenant,InventoryMovementSearch filter,
            int page,int size) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return history.searchMovements(actor.userId(),tenant,filter,page,size);
    }

    public Page<InventoryReservationEventDto> reservationHistory(
            VerifiedActor actor,UUID tenant,UUID reservationId,int page,int size) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return history.reservationHistory(actor.userId(),tenant,reservationId,page,size);
    }    public InventoryStocktakeDto stocktake(VerifiedActor actor,UUID tenant,UUID id) {
        if(actor==null)throw new SecurityException("Verified actor required");
        return history.stocktake(actor.userId(),tenant,id);
    }
}
