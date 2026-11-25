package kz.rm.geo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Shared static directory with no tenant-owned or PII records. */
@Service
@RequiredArgsConstructor
public class KatoTerritoryDirectory {
    private final KatoTerritoryRepository repo;

    @Transactional(readOnly=true)
    public List<KatoTerritoryView> eligibleForOrganizationLocation() {
        var rows=repo.findAll();
        var byId=new java.util.HashMap<Long,KatoTerritoryEntity>();
        rows.forEach(row->byId.put(row.getSourceId(),row));
        return rows.stream().filter(x -> KatoHierarchy.eligible(x,byId::get))
                .sorted(java.util.Comparator.comparing(KatoTerritoryEntity::getCode))
                .map(KatoTerritoryView::from).toList();
    }
}
