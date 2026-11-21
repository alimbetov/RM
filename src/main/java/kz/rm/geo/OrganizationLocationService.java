package kz.rm.geo;

import kz.rm.iam.WorkspaceEntity;
import kz.rm.iam.WorkspaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Tenant validation belongs to the service; caller authorization is a separate IAM gate. */
@Service
@RequiredArgsConstructor
public class OrganizationLocationService {
    private final WorkspaceRepository workspaces;
    private final KatoTerritoryRepository kato;
    private final OrganizationLocationRepository locations;

    @Transactional
    public OrganizationLocationEntity create(UUID workspaceId, OrganizationLocationEntity.Type type,
                                             String name, String katoCode,String addressLine) {
        if (workspaceId == null) throw new IllegalArgumentException("Workspace required");
        var workspace=workspaces.findById(workspaceId)
                .orElseThrow(()->new IllegalArgumentException("Workspace not found"));
        if(workspace.getKind()!=WorkspaceEntity.Kind.ORGANIZATION)
            throw new IllegalArgumentException("Only organization workspaces own branches and warehouses");
        var territory=kato.findByCodeAndDeletedFalse(katoCode)
            .orElseThrow(() -> new IllegalArgumentException("Unknown or deleted KATO code"));
        if(territory.getLevel()>3)
            throw new IllegalArgumentException("KATO selection limited to oblast/district level");
        return locations.saveAndFlush(new OrganizationLocationEntity(
                UUID.randomUUID(),workspaceId,type,name,katoCode,addressLine));
    }
}
