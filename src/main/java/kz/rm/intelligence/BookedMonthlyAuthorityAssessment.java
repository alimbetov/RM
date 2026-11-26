package kz.rm.intelligence;

import java.time.YearMonth;
import java.util.*;

/** Monthly candidate assessment, not historical admission. No OwnerProof is created. */
public final class BookedMonthlyAuthorityAssessment {
    private BookedMonthlyAuthorityAssessment(){}
    public static final int MAX_PROOFS=4;
    public enum State { NO_DECLARATIONS,OPEN_PERIOD,MIXED_HISTORY_MODE,DUPLICATE_RUN,
        INVALID_PARTITION,DECLARATION_NOT_CURRENT,MONTHLY_POPULATION_MISMATCH,
        NATIVE_BOUNDARY_INVALID,SOURCE_AUTHORITY_REQUIRED }
    public record Slice(BookedSourceReconciliationService.Evidence reconciliation,
                        BookedOwnerDeclarationService.Inspection declaration){
        public Slice {
            Objects.requireNonNull(reconciliation);Objects.requireNonNull(declaration);
            if(!reconciliation.tenant().equals(declaration.tenant())||!reconciliation.actor().equals(declaration.actor())||
                !reconciliation.runId().equals(declaration.runId())||
                reconciliation.sourceRevision()!=declaration.sourceRevision()||
                reconciliation.receiptRevision()!=declaration.receiptRevision()||
                !reconciliation.asOf().equals(declaration.asOf()))
                throw new IllegalArgumentException("S1_MONTHLY_SLICE_SCOPE");
        }
    }
    public record Month(HistoricalCoverageService.IntervalEvidence history,State state,List<Slice> slices,
                        List<BookedHistoryComposition.Interval> gaps,List<BookedHistoryComposition.Issue> issues){
        public Month {slices=List.copyOf(slices);gaps=List.copyOf(gaps);issues=List.copyOf(issues);}
        public boolean ownerCompletenessProven(){return false;}
    }
    public record Result(HistoricalCoverageService.Assessment history,List<Month> months){
        public Result {months=List.copyOf(months);}
        public boolean admitted(){return false;}
    }
    static Map<YearMonth,List<UUID>> request(List<HistoricalCoverageService.RequiredMonth> required,
                                            Map<YearMonth,List<UUID>> proofIds){
        var keys=new HashSet<>(required.stream().map(HistoricalCoverageService.RequiredMonth::month).toList());
        var out=new HashMap<YearMonth,List<UUID>>();var seen=new HashSet<UUID>();
        for(var e:Map.copyOf(proofIds).entrySet()){
            if(!keys.contains(e.getKey()))throw new IllegalArgumentException("S1_MONTHLY_UNREQUESTED_PROOF");
            var ids=List.copyOf(e.getValue());
            for(var id:ids)if(!seen.add(id)||seen.size()>MAX_PROOFS)
                throw new IllegalArgumentException("S1_MONTHLY_PROOF_BUDGET");
            out.put(e.getKey(),ids);
        }
        return Map.copyOf(out);
    }
    static Result assess(HistoricalCoverageService.Assessment history,Map<YearMonth,List<Slice>> byMonth){
        if(history.qualified()!=0||history.intervals().stream().anyMatch(i->i.quality()==DataQuality.READY))
            throw new IllegalArgumentException("S1_MONTHLY_DIAGNOSTIC_ONLY");
        var manifest=history.manifest();
        var snapshot=new BookedHistoryComposition.Snapshot(manifest.tenant(),manifest.actor(),manifest.tenantCreatedAt(),
            manifest.sourceRevision(),manifest.asOf());
        var months=new ArrayList<Month>();
        for(var m:history.intervals()){
            var slices=List.copyOf(byMonth.getOrDefault(m.requirement().month(),List.of()));
            for(var slice:slices){
                var e=slice.reconciliation();
                if(!e.tenant().equals(manifest.tenant())||!e.actor().equals(manifest.actor())||
                    e.sourceRevision()!=manifest.sourceRevision()||!e.asOf().equals(manifest.asOf())||
                    e.version()!=BookedSourceReconciliationService.VERSION)
                    throw new IllegalArgumentException("S1_MONTHLY_SNAPSHOT");
            }
            var requested=new BookedHistoryComposition.Interval(m.interval().startInclusive(),m.interval().endExclusive());
            if(requested.until().isAfter(manifest.asOf())){months.add(simple(m,State.OPEN_PERIOD,slices));continue;}
            if(slices.isEmpty()){months.add(simple(m,State.NO_DECLARATIONS,slices));continue;}
            if(slices.stream().map(x->x.declaration().mode()).distinct().count()!=1){
                months.add(simple(m,State.MIXED_HISTORY_MODE,slices));continue;
            }
            if(slices.stream().map(x->x.reconciliation().runId()).distinct().count()!=slices.size()){
                months.add(simple(m,State.DUPLICATE_RUN,slices));continue;
            }
            var composed=BookedHistoryComposition.assess(snapshot,slices.getFirst().declaration().mode(),requested,
                slices.stream().map(Slice::reconciliation).toList());
            State state;
            if(!composed.exactReconciledPartition())state=State.INVALID_PARTITION;
            else if(composed.boundary()==BookedHistoryComposition.Boundary.NATIVE_BEFORE_TENANT_CREATION)
                state=State.NATIVE_BOUNDARY_INVALID;
            else if(slices.stream().anyMatch(x->x.declaration().state()!=
                BookedOwnerDeclarationService.State.MATCHED_CONTRACT_NOT_ADMITTED))state=State.DECLARATION_NOT_CURRENT;
            else{
                long observed=0;
                for(var slice:slices)observed=Math.addExact(observed,slice.reconciliation().counts().distinctValidOrders());
                var independent=m.source();
                state=!independent.identityParity()||independent.invalidOwnerEvents()!=0||
                    independent.ownerEvents()!=independent.distinctValidOrders()||independent.distinctValidOrders()!=observed?
                    State.MONTHLY_POPULATION_MISMATCH:State.SOURCE_AUTHORITY_REQUIRED;
            }
            months.add(new Month(m,state,slices,composed.gaps(),composed.issues()));
        }
        return new Result(history,months);
    }
    private static Month simple(HistoricalCoverageService.IntervalEvidence m,State state,List<Slice> slices){
        return new Month(m,state,slices,List.of(),List.of());
    }
}
