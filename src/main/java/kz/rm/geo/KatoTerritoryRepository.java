package kz.rm.geo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KatoTerritoryRepository extends JpaRepository<KatoTerritoryEntity,String> {
    Optional<KatoTerritoryEntity> findByCodeAndDeletedFalse(String code);
    Optional<KatoTerritoryEntity> findBySourceId(Long sourceId);
    List<KatoTerritoryEntity> findByParentSourceIdAndDeletedFalseOrderByCode(Long parentSourceId);
}
