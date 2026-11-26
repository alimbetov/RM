package kz.rm.iam;

import kz.rm.intelligence.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

final class BookedMonthlyJourneyEvidence {
    private BookedMonthlyJourneyEvidence(){}
    private static UUID declare(BookedOwnerDeclarationService declarations,VerifiedActor owner,UUID tenant,
                                BookedSourceReconciliationService.Evidence e){
        var id=UUID.randomUUID();
        return declarations.declare(owner,tenant,new BookedOwnerDeclarationService.Draft(id,e.runId(),
            BookedHistoryComposition.Mode.IMPORTED_HISTORY,"SYNTHETIC_MONTH_MANIFEST","b".repeat(64),
            e.sourceRevision(),e.receiptRevision(),e.counts().distinctValidOrders(),e.digest().populationDigest()));
    }
    static void qualify(CanonicalFactDeliveryService delivery,BookedSourceReconciliationService reconciliation,
                        BookedOwnerDeclarationService declarations,VerifiedActor owner,VerifiedActor reader,UUID tenant,long calendar,
                        VerifiedActor otherOwner,VerifiedActor otherReader,UUID otherTenant,long otherCalendar,Instant from,UUID shortRun){
        YearMonth month=YearMonth.from(from.atZone(ZoneOffset.UTC));
        Instant until=month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        UUID full=UUID.randomUUID();
        for(UUID t:List.of(tenant,otherTenant))delivery.openBackfill(new CanonicalFactDeliveryService.Slice(t,full,
            CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,from,until));
        assertThat(delivery.backfillBatch(tenant,full,10)).hasSize(2);
        assertThat(delivery.backfillBatch(otherTenant,full,10)).isEmpty();
        var positive=declare(declarations,owner,tenant,reconciliation.inspect(reader,tenant,full));
        var zero=declare(declarations,otherOwner,otherTenant,reconciliation.inspect(otherReader,otherTenant,full));
        var shortId=declare(declarations,owner,tenant,reconciliation.inspect(reader,tenant,shortRun));
        var required=List.of(new HistoricalCoverageService.RequiredMonth(month,HistoricalCoverageService.Role.TARGET),
            new HistoricalCoverageService.RequiredMonth(month.minusMonths(1),HistoricalCoverageService.Role.BASELINE));
        var a=reconciliation.inspectMonthlyAuthority(reader,tenant,calendar,required,Map.of(month,List.of(positive)));
        assertThat(a.history().qualified()).isZero();assertThat(a.admitted()).isFalse();
        assertThat(a.months().getFirst().state()).isEqualTo(BookedMonthlyAuthorityAssessment.State.NO_DECLARATIONS);
        var target=a.months().getLast();
        assertThat(target.state()).isEqualTo(BookedMonthlyAuthorityAssessment.State.SOURCE_AUTHORITY_REQUIRED);
        assertThat(target.history().source().distinctValidOrders()).isEqualTo(2);
        assertThat(target.history().value()).isEqualByComparingTo("2");
        assertThat(target.history().quality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(target.slices().getFirst().reconciliation().asOf()).isEqualTo(a.history().manifest().asOf());
        assertThat(target.slices().getFirst().reconciliation().sourceRevision()).isEqualTo(a.history().manifest().sourceRevision());
        var z=reconciliation.inspectMonthlyAuthority(otherReader,otherTenant,otherCalendar,required,Map.of(month,List.of(zero)));
        assertThat(z.months().getLast().state()).isEqualTo(BookedMonthlyAuthorityAssessment.State.SOURCE_AUTHORITY_REQUIRED);
        assertThat(z.months().getLast().history().value()).isNull();
        assertThat(z.months().getLast().history().quality()).isEqualTo(DataQuality.MISSING);
        var gap=reconciliation.inspectMonthlyAuthority(reader,tenant,calendar,required,Map.of(month,List.of(shortId)));
        assertThat(gap.months().getLast().state()).isEqualTo(BookedMonthlyAuthorityAssessment.State.INVALID_PARTITION);
        assertThat(gap.months().getLast().gaps()).hasSize(1);
        assertThatThrownBy(()->reconciliation.inspectMonthlyAuthority(reader,tenant,calendar,required,Map.of(month,List.of(zero))))
            .hasMessage("S1_DECLARATION_NOT_FOUND");
        assertThatThrownBy(()->reconciliation.inspectMonthlyAuthority(otherReader,tenant,calendar,required,Map.of(month,List.of(positive))))
            .isInstanceOf(SecurityException.class);
        declarations.revoke(owner,tenant,positive);
        var revoked=reconciliation.inspectMonthlyAuthority(reader,tenant,calendar,required,Map.of(month,List.of(positive)));
        assertThat(revoked.months().getLast().state()).isEqualTo(BookedMonthlyAuthorityAssessment.State.DECLARATION_NOT_CURRENT);
        assertThat(revoked.history().qualified()).isZero();
        System.out.println("S1_MONTHLY_AUTHORITY_PASS positive=2 zero=MISSING gap revoked foreign blocked");
    }
}
