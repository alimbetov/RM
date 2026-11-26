package kz.rm.intelligence;

import java.time.Instant;
import java.util.*;
import static kz.rm.intelligence.BookedOwnerAuthorityContract.*;

/** Candidate input contracts for future trusted source adapters.
 * Structural evidence is not authentication, capture proof, import completion or runtime admission.
 * No public ingestion and no path from these types to HistoricalCoverageService.OwnerProof. */
final class BookedSourceAuthorityInputs {
    private BookedSourceAuthorityInputs(){}
    enum Result { MISSING_SOURCE,INCOMPATIBLE_SCOPE,INVALID_BOUNDARY,POPULATION_MISMATCH,
        MATCHED_SOURCE_CONTRACT_NOT_ADMITTED }
    sealed interface Source permits NativeCapture,ImportedManifest {
        UUID tenant();
        Instant from();
        Instant until();
        long expectedOrders();
        String expectedPopulationDigest();
        int authorityVersion();
    }
    record NativeCapture(UUID tenant,Instant from,Instant until,long expectedOrders,String expectedPopulationDigest,
                         int authorityVersion,Instant captureStartedAt,String capturePolicyRef,String closureRef,
                         String captureEvidenceDigest) implements Source {
        NativeCapture {
            validate(tenant,from,until,expectedOrders,expectedPopulationDigest);
            Objects.requireNonNull(captureStartedAt);
            requireRef(capturePolicyRef);requireRef(closureRef);requireDigest(captureEvidenceDigest);
        }
    }
    record ImportedManifest(UUID tenant,Instant from,Instant until,long expectedOrders,String expectedPopulationDigest,
                            int authorityVersion,String sourceSystem,String exportId,String artifactDigest,
                            String mappingVersion,String completionRef) implements Source {
        ImportedManifest {
            validate(tenant,from,until,expectedOrders,expectedPopulationDigest);
            requireRef(sourceSystem);requireRef(exportId);requireDigest(artifactDigest);
            requireRef(mappingVersion);requireRef(completionRef);
        }
    }
    private static void validate(UUID tenant,Instant from,Instant until,long expected,String digest){
        Objects.requireNonNull(tenant);Objects.requireNonNull(from);Objects.requireNonNull(until);
        if(!from.isBefore(until)||expected<0)throw new IllegalArgumentException("S1_SOURCE_INPUT_RANGE");
        requireDigest(digest);
    }
    private static void requireRef(String s){
        if(s==null||s.isBlank()||s.length()>256)throw new IllegalArgumentException("S1_SOURCE_INPUT_REF");
    }
    private static void requireDigest(String s){
        if(s==null||!s.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("S1_SOURCE_INPUT_DIGEST");
    }
    // Exact bounds: a wider manifest digest cannot prove a narrower slice without a
    // separately qualified enumeration. These checks do not authenticate the source.
    static Result compare(Claim claim,Source input,Instant tenantCreatedAt){
        Objects.requireNonNull(claim);Objects.requireNonNull(tenantCreatedAt);
        if(input==null)return Result.MISSING_SOURCE;
        var scope=claim.revision();
        if(!scope.tenant().equals(input.tenant())||!scope.from().equals(input.from())||!scope.until().equals(input.until())||
            claim.authorityVersion()!=input.authorityVersion()||profile(input.authorityVersion())==null||
            (claim.mode()==Mode.NATIVE_HISTORY)!=(input instanceof NativeCapture))
            return Result.INCOMPATIBLE_SCOPE;
        if(input.until().isAfter(claim.issuedAt()))return Result.INVALID_BOUNDARY;
        if(input instanceof NativeCapture n){
            if(n.captureStartedAt().isBefore(tenantCreatedAt)||n.captureStartedAt().isAfter(n.from())||
                !n.captureEvidenceDigest().equals(claim.provenanceDigest()))return Result.INVALID_BOUNDARY;
        }else if(input instanceof ImportedManifest i){
            if(!i.artifactDigest().equals(claim.provenanceDigest()))return Result.INVALID_BOUNDARY;
        }
        if(claim.expectedOrders()!=input.expectedOrders()||
            !claim.expectedPopulationDigest().equals(input.expectedPopulationDigest()))
            return Result.POPULATION_MISMATCH;
        return Result.MATCHED_SOURCE_CONTRACT_NOT_ADMITTED;
    }
}
