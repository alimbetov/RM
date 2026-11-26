package kz.rm.intelligence;

import java.time.Instant;
import java.util.*;
import static kz.rm.intelligence.BookedSourceReconciliationService.*;

/** Internal owner-authority contract. Structural matching is deliberately NOT
 * issuer authentication, durable admission, or a runtime HistoricalCoverage OwnerProof.
 * No production adapter is admitted yet; no caller-supplied claim can promote READY. */
final class BookedOwnerAuthorityContract {
    static final int AUTHORITY_VERSION=1;
    // Immutable interpretation of the persisted authority_version=1 in changeset 0222.
    // Do not derive these coordinates from mutable current-version constants.
    record VersionProfile(int authority,int reconciliation,int populationEncoding,int methodVersion,
                          String metric,String scope,String stream,String timeBasis,String unit){}
    private static final VersionProfile V1=new VersionProfile(1,2,1,1,"BOOKED_ORDERS",
        "TENANT_SALES_READ","SALES/ORDER_PLACED","OCCURRED","COUNT");
    static VersionProfile profile(int authorityVersion){return authorityVersion==1?V1:null;}
    static boolean compatibleVersion(int authorityVersion,int reconciliationVersion,int populationEncoding,int methodVersion){
        var p=profile(authorityVersion);
        return p!=null&&p.reconciliation()==reconciliationVersion&&p.populationEncoding()==populationEncoding&&p.methodVersion()==methodVersion;
    }
    enum Mode { NATIVE_HISTORY,IMPORTED_HISTORY }
    enum Status { OWNER_AUTHORITY_MISSING,INCOMPATIBLE_SCOPE,STALE_REVISION,INVALID_PROVENANCE,
        OPEN_INTERVAL,UNRECONCILED_SOURCE,POPULATION_MISMATCH,MATCHED_CONTRACT_NOT_ADMITTED }
    /** Provenance must eventually resolve to independently admitted owner storage.
     * Native evidence references the capture/closure contract, imported evidence an export/import manifest.
     * Digest alone is not authentication. */
    record Claim(UUID proofId,int authorityVersion,Mode mode,String issuer,String provenanceRef,
                 String provenanceDigest,RevisionBinding revision,Instant sourceFrom,Instant sourceUntil,
                 Instant issuedAt,long expectedOrders,String expectedPopulationDigest){
        Claim {
            Objects.requireNonNull(proofId);Objects.requireNonNull(mode);Objects.requireNonNull(revision);
            Objects.requireNonNull(sourceFrom);Objects.requireNonNull(sourceUntil);Objects.requireNonNull(issuedAt);
            if(issuer==null||issuer.isBlank()||issuer.length()>128||provenanceRef==null||provenanceRef.isBlank()||
                provenanceRef.length()>256||!hex(provenanceDigest)||!hex(expectedPopulationDigest)||
                expectedOrders<0||!sourceFrom.isBefore(sourceUntil))
                throw new IllegalArgumentException("S1_AUTHORITY_CLAIM");
        }
    }
    record Assessment(Status status,UUID proofId,HistoricalCoverageService.Eligibility eligibility){
        // Even a matching claim is not proof of an admitted issuer.
        boolean admitted(){return false;}
    }
    private static boolean hex(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
    /** Pure, package-internal compatibility check for a future trusted adapter.
     * A matching claim never becomes an OwnerProof here. */
    static Assessment evaluate(Evidence current,Claim claim){
        Objects.requireNonNull(current);
        if(claim==null)return result(Status.OWNER_AUTHORITY_MISSING,null);
        var expected=current.revisionBinding();var supplied=claim.revision();
        if(!expected.tenant().equals(supplied.tenant())||!expected.runId().equals(supplied.runId())||
            !expected.from().equals(supplied.from())||!expected.until().equals(supplied.until())||
            claim.sourceFrom().isAfter(expected.from())||claim.sourceUntil().isBefore(expected.until()))
            return result(Status.INCOMPATIBLE_SCOPE,claim);
        if(expected.sourceRevision()!=supplied.sourceRevision()||expected.receiptRevision()!=supplied.receiptRevision())
            return result(Status.STALE_REVISION,claim);
        if(current.until().isAfter(current.asOf()))return result(Status.OPEN_INTERVAL,claim);
        if(!compatibleVersion(claim.authorityVersion(),current.version(),IndependentCanonicalDigestVerifier.BOOKED_POPULATION_ENCODING_VERSION,1)||
            current.version()!=BookedSourceReconciliationService.VERSION||
            claim.issuedAt().isAfter(current.asOf())||claim.sourceUntil().isAfter(claim.issuedAt()))
            return result(Status.INVALID_PROVENANCE,claim);
        if(current.state()!=State.RECONCILED_PRESENT_SOURCE||current.digest().budgetExceeded()||
            current.digest().mismatches()!=0||current.digest().legacy()!=0)
            return result(Status.UNRECONCILED_SOURCE,claim);
        if(claim.expectedOrders()!=current.counts().distinctValidOrders()||
            !claim.expectedPopulationDigest().equals(current.digest().populationDigest()))
            return result(Status.POPULATION_MISMATCH,claim);
        return result(Status.MATCHED_CONTRACT_NOT_ADMITTED,claim);
    }
    private static Assessment result(Status status,Claim claim){
        return new Assessment(status,claim==null?null:claim.proofId(),HistoricalCoverageService.Eligibility.BLOCKED);
    }
}
