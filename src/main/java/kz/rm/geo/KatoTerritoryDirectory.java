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
        return repo.findAll().stream().filter(x -> !x.isDeleted() && x.getLevel() <= 3)
                .sorted(java.util.Comparator.comparing(KatoTerritoryEntity::getCode))
                .map(KatoTerritoryView::from).toList();
    }
}
