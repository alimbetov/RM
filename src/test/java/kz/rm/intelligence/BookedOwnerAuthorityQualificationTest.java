package kz.rm.intelligence;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static kz.rm.intelligence.BookedSourceReconciliationService.*;
import static kz.rm.intelligence.BookedOwnerAuthorityContract.*;

class BookedOwnerAuthorityQualificationTest {
    private static final UUID T=new UUID(0,1),A=new UUID(0,2),R=new UUID(0,3);
    private static final Instant FROM=Instant.parse("2026-09-01T00:00:00Z"),UNTIL=Instant.parse("2026-10-01T00:00:00Z"),NOW=UNTIL.plusSeconds(1);
    private static final String DIGEST="a".repeat(64);
    private Evidence evidence(UUID tenant,long source,long receipt,long n,boolean missing){
        return classify(tenant,A,R,FROM,UNTIL,source,receipt,NOW,new Counts(n,n,0,0,missing?1:0,0,0,0,n,0),
            new Digest(n,0,0,false,DIGEST));
    }
    private Claim claim(Evidence e,Mode mode,long count,String population){
        return new Claim(new UUID(0,4),AUTHORITY_VERSION,mode,"SYNTHETIC_OWNER","SYNTHETIC_MANIFEST","b".repeat(64),
            e.revisionBinding(),FROM,UNTIL,UNTIL,count,population);
    }
    @Test void structurallyMatchingClaimsNeverAdmitRuntimeAuthority(){
        for(var mode:Mode.values())for(long n:List.of(0L,2L)){
            var e=evidence(T,7,n,n,false);
            var result=evaluate(e,claim(e,mode,n,DIGEST));
            assertThat(result.status()).isEqualTo(Status.MATCHED_CONTRACT_NOT_ADMITTED);
            assertThat(result.admitted()).isFalse();
            assertThat(result.eligibility()).isEqualTo(HistoricalCoverageService.Eligibility.BLOCKED);
            assertThat(e.ownerCompletenessProven()).isFalse();
        }
    }
    @Test void absentAndForeignAuthorityAreRejected(){
        var e=evidence(T,7,2,2,false);
        assertThat(evaluate(e,null).status()).isEqualTo(Status.OWNER_AUTHORITY_MISSING);
        assertThat(evaluate(e,claim(evidence(new UUID(0,9),7,2,2,false),Mode.NATIVE_HISTORY,2,DIGEST)).status())
            .isEqualTo(Status.INCOMPATIBLE_SCOPE);
    }
    @Test void sourceAndReceiptRevisionsIndependentlyInvalidateClaims(){
        var e=evidence(T,7,2,2,false);
        var prior=claim(e,Mode.NATIVE_HISTORY,2,DIGEST);
        assertThat(evaluate(evidence(T,8,2,2,false),prior).status()).isEqualTo(Status.STALE_REVISION);
        var incomplete=evidence(T,7,1,2,true);
        assertThat(evaluate(e,claim(incomplete,Mode.NATIVE_HISTORY,2,DIGEST)).status()).isEqualTo(Status.STALE_REVISION);
    }
    @Test void countAndPopulationMustBothMatch(){
        var e=evidence(T,7,2,2,false);
        assertThat(evaluate(e,claim(e,Mode.IMPORTED_HISTORY,1,DIGEST)).status()).isEqualTo(Status.POPULATION_MISMATCH);
        assertThat(evaluate(e,claim(e,Mode.IMPORTED_HISTORY,2,"c".repeat(64))).status()).isEqualTo(Status.POPULATION_MISMATCH);
        var incomplete=evidence(T,7,1,2,true);
        assertThat(evaluate(incomplete,claim(incomplete,Mode.IMPORTED_HISTORY,2,DIGEST)).status()).isEqualTo(Status.UNRECONCILED_SOURCE);
    }
    @Test void futureIssuanceAndUncoveredIntervalCannotMatch(){
        var e=evidence(T,7,2,2,false);
        var c=claim(e,Mode.IMPORTED_HISTORY,2,DIGEST);
        var future=new Claim(c.proofId(),c.authorityVersion(),c.mode(),c.issuer(),c.provenanceRef(),c.provenanceDigest(),
            c.revision(),FROM,UNTIL,NOW.plusSeconds(1),2,DIGEST);
        assertThat(evaluate(e,future).status()).isEqualTo(Status.INVALID_PROVENANCE);
        var gap=new Claim(c.proofId(),c.authorityVersion(),c.mode(),c.issuer(),c.provenanceRef(),c.provenanceDigest(),
            c.revision(),FROM.plusSeconds(1),UNTIL,UNTIL,2,DIGEST);
        assertThat(evaluate(e,gap).status()).isEqualTo(Status.INCOMPATIBLE_SCOPE);
        var open=classify(T,A,R,FROM,UNTIL,7,2,UNTIL.minusSeconds(1),e.counts(),e.digest());
        assertThat(evaluate(open,c).status()).isEqualTo(Status.OPEN_INTERVAL);
    }

    private BookedOwnerDeclarationService.Stored stored(UUID tenant,UUID run,int version,boolean revoked){
        var draft=new BookedOwnerDeclarationService.Draft(new UUID(0,4),run,BookedHistoryComposition.Mode.IMPORTED_HISTORY,
            "SYNTHETIC_MANIFEST","b".repeat(64),7,2,2,DIGEST);
        return new BookedOwnerDeclarationService.Stored(tenant,A,version,draft,FROM,UNTIL,UNTIL,revoked);
    }
    @Test void persistedTenantAndRunCannotBeRelabelledEvenWhenRevoked(){
        var e=evidence(T,7,2,2,false);
        for(boolean revoked:List.of(false,true)){
            var foreign=stored(new UUID(0,9),R,AUTHORITY_VERSION,revoked);
            var wrongRun=stored(T,new UUID(0,9),AUTHORITY_VERSION,revoked);
            for(var row:List.of(foreign,wrongRun))
                assertThatThrownBy(()->BookedOwnerDeclarationService.inspect(row,e))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("S1_DECLARATION_SCOPE");
        }
    }
    @Test void persistedAuthorityVersionCannotBeReplacedByCurrentVersion(){
        var e=evidence(T,7,2,2,false);
        var result=BookedOwnerDeclarationService.inspect(stored(T,R,AUTHORITY_VERSION+1,false),e);
        assertThat(result.state()).isEqualTo(BookedOwnerDeclarationService.State.INVALID_PROVENANCE);
        assertThat(result.admitted()).isFalse();
        assertThat(result.eligibility()).isEqualTo(HistoricalCoverageService.Eligibility.BLOCKED);
        var match=BookedOwnerDeclarationService.inspect(stored(T,R,AUTHORITY_VERSION,false),e);
        assertThat(match.state()).isEqualTo(BookedOwnerDeclarationService.State.MATCHED_CONTRACT_NOT_ADMITTED);
        assertThat(match.admitted()).isFalse();
    }

    @Test void persistedProfileIsFrozenAndRejectsAlgorithmDrift(){
        var p=profile(1);
        assertThat(p.metric()).isEqualTo("BOOKED_ORDERS");
        assertThat(p.stream()).isEqualTo("SALES/ORDER_PLACED");
        assertThat(p.timeBasis()).isEqualTo("OCCURRED");
        assertThat(p.unit()).isEqualTo("COUNT");
        assertThat(compatibleVersion(1,2,1,1)).isTrue();
        assertThat(compatibleVersion(2,2,1,1)).isFalse();
        assertThat(compatibleVersion(1,3,1,1)).isFalse();
        assertThat(compatibleVersion(1,2,2,1)).isFalse();
        assertThat(compatibleVersion(1,2,1,2)).isFalse();
    }
    @Test void nativeCaptureAndImportedManifestAreDistinctUnadmittedContracts(){
        var e=evidence(T,7,2,2,false);
        var nativeClaim=claim(e,Mode.NATIVE_HISTORY,2,DIGEST);
        var importedClaim=claim(e,Mode.IMPORTED_HISTORY,2,DIGEST);
        var nativeInput=new BookedSourceAuthorityInputs.NativeCapture(T,FROM,UNTIL,2,DIGEST,1,FROM,
            "CAPTURE_POLICY","CLOSURE","b".repeat(64));
        var importedInput=new BookedSourceAuthorityInputs.ImportedManifest(T,FROM,UNTIL,2,DIGEST,1,
            "ERP","EXPORT","b".repeat(64),"MAP_V1","IMPORT_COMPLETION");
        assertThat(BookedSourceAuthorityInputs.compare(nativeClaim,nativeInput,FROM))
            .isEqualTo(BookedSourceAuthorityInputs.Result.MATCHED_SOURCE_CONTRACT_NOT_ADMITTED);
        assertThat(BookedSourceAuthorityInputs.compare(nativeClaim,nativeInput,FROM.plusSeconds(1)))
            .isEqualTo(BookedSourceAuthorityInputs.Result.INVALID_BOUNDARY);
        assertThat(BookedSourceAuthorityInputs.compare(importedClaim,importedInput,FROM.plusSeconds(1)))
            .isEqualTo(BookedSourceAuthorityInputs.Result.MATCHED_SOURCE_CONTRACT_NOT_ADMITTED);
        assertThat(BookedSourceAuthorityInputs.compare(nativeClaim,importedInput,FROM))
            .isEqualTo(BookedSourceAuthorityInputs.Result.INCOMPATIBLE_SCOPE);
        assertThat(BookedSourceAuthorityInputs.compare(importedClaim,null,FROM))
            .isEqualTo(BookedSourceAuthorityInputs.Result.MISSING_SOURCE);
        assertThat(evaluate(e,importedClaim).admitted()).isFalse();
    }
    @Test void importedManifestCannotSubstituteForeignOrDifferentPopulation(){
        var e=evidence(T,7,2,2,false);
        var cl=claim(e,Mode.IMPORTED_HISTORY,2,DIGEST);
        for(var input:List.of(
            new BookedSourceAuthorityInputs.ImportedManifest(new UUID(0,9),FROM,UNTIL,2,DIGEST,1,"ERP","E","b".repeat(64),"M","C"),
            new BookedSourceAuthorityInputs.ImportedManifest(T,FROM.minusSeconds(1),UNTIL,2,DIGEST,1,"ERP","E","b".repeat(64),"M","C")))
            assertThat(BookedSourceAuthorityInputs.compare(cl,input,FROM))
                .isEqualTo(BookedSourceAuthorityInputs.Result.INCOMPATIBLE_SCOPE);
        var wrong=new BookedSourceAuthorityInputs.ImportedManifest(T,FROM,UNTIL,3,DIGEST,1,"ERP","E","b".repeat(64),"M","C");
        assertThat(BookedSourceAuthorityInputs.compare(cl,wrong,FROM))
            .isEqualTo(BookedSourceAuthorityInputs.Result.POPULATION_MISMATCH);
        var different=new BookedSourceAuthorityInputs.ImportedManifest(T,FROM,UNTIL,2,DIGEST,1,"ERP","E","c".repeat(64),"M","C");
        assertThat(BookedSourceAuthorityInputs.compare(cl,different,FROM))
            .isEqualTo(BookedSourceAuthorityInputs.Result.INVALID_BOUNDARY);
    }
}
