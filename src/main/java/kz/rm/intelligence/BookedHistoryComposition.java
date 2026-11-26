package kz.rm.intelligence;

import java.time.*;
import java.util.*;
import static kz.rm.intelligence.BookedSourceReconciliationService.*;

/** Diagnostic exact-interval composition only. No authority issuance or metric READY. */
public final class BookedHistoryComposition {
    public static final int MAX_RUNS=4;
    public static final Duration MAX_RANGE=Duration.ofDays(124);
    public enum Mode { NATIVE_HISTORY,IMPORTED_HISTORY }
    public enum Boundary { OPEN_INTERVAL,NATIVE_BEFORE_TENANT_CREATION,IMPORTED_PRE_TENANT_AUTHORITY_REQUIRED,OWNER_BOUNDARY_REQUIRED }
    public enum Issue { GAP,OVERLAP,OUTSIDE_REQUEST,UNRECONCILED_SLICE,INCOMPATIBLE_SNAPSHOT }
    public record Interval(Instant from,Instant until){
        public Interval {Objects.requireNonNull(from);Objects.requireNonNull(until);
            if(!from.isBefore(until))throw new IllegalArgumentException("S1_INTERVAL");}
    }
    public record Snapshot(UUID tenant,UUID actor,Instant tenantCreatedAt,long sourceRevision,Instant asOf){
        public Snapshot {Objects.requireNonNull(tenant);Objects.requireNonNull(actor);Objects.requireNonNull(tenantCreatedAt);
            Objects.requireNonNull(asOf);if(sourceRevision<0||tenantCreatedAt.isAfter(asOf))throw new IllegalArgumentException("S1_SNAPSHOT");}
    }
    public record Assessment(Snapshot snapshot,Mode mode,Interval requested,Boundary boundary,List<Evidence> slices,
                             List<Interval> gaps,List<Issue> issues,Long reconciledObservedOrders,
                             HistoricalCoverageService.Eligibility eligibility){
        public Assessment {slices=List.copyOf(slices);gaps=List.copyOf(gaps);issues=List.copyOf(issues);}
        public boolean exactReconciledPartition(){return issues.isEmpty();}
        public boolean ownerCompletenessProven(){return false;}
    }
    private BookedHistoryComposition(){}
    static Interval request(Instant from,Instant until){
        var interval=new Interval(from,until);
        if(Duration.between(from,until).compareTo(MAX_RANGE)>0)throw new IllegalArgumentException("S1_COMPOSITION_RANGE");
        return interval;
    }
    static Boundary boundary(Snapshot snapshot,Mode mode,Interval requested){
        Objects.requireNonNull(mode);
        if(requested.until().isAfter(snapshot.asOf()))return Boundary.OPEN_INTERVAL;
        if(requested.from().isBefore(snapshot.tenantCreatedAt()))return mode==Mode.NATIVE_HISTORY?
            Boundary.NATIVE_BEFORE_TENANT_CREATION:Boundary.IMPORTED_PRE_TENANT_AUTHORITY_REQUIRED;
        return Boundary.OWNER_BOUNDARY_REQUIRED;
    }
    /** Only called on freshly read same-snapshot evidence, or by pure qualification tests.
     * Union gaps are calculated exactly. Overlaps never yield a summed count. */
    static Assessment assess(Snapshot snapshot,Mode mode,Interval requested,List<Evidence> evidence){
        request(requested.from(),requested.until());Objects.requireNonNull(snapshot);Objects.requireNonNull(mode);
        var copy=List.copyOf(evidence);
        if(copy.size()>MAX_RUNS||copy.stream().map(Evidence::runId).distinct().count()!=copy.size())
            throw new IllegalArgumentException("S1_COMPOSITION_RUNS");
        var sorted=copy.stream().sorted(Comparator.comparing(Evidence::from).thenComparing(Evidence::until)).toList();
        var issues=EnumSet.noneOf(Issue.class);var gaps=new ArrayList<Interval>();
        Instant cursor=requested.from();long observed=0;
        for(var e:sorted){
            if(!snapshot.tenant().equals(e.tenant())||!snapshot.actor().equals(e.actor())||
                snapshot.sourceRevision()!=e.sourceRevision()||!snapshot.asOf().equals(e.asOf())||
                e.version()!=BookedSourceReconciliationService.VERSION){
                // Never return foreign or stale-snapshot evidence as part of an envelope.
                throw new IllegalArgumentException("S1_COMPOSITION_SNAPSHOT");
            }
            if(e.from().isBefore(requested.from())||e.until().isAfter(requested.until()))issues.add(Issue.OUTSIDE_REQUEST);
            if(e.state()!=State.RECONCILED_PRESENT_SOURCE)issues.add(Issue.UNRECONCILED_SLICE);
            Instant start=e.from().isAfter(requested.from())?e.from():requested.from();
            Instant end=e.until().isBefore(requested.until())?e.until():requested.until();
            if(!start.isBefore(end))continue;
            if(start.isAfter(cursor)){gaps.add(new Interval(cursor,start));issues.add(Issue.GAP);}
            if(start.isBefore(cursor))issues.add(Issue.OVERLAP);
            if(end.isAfter(cursor))cursor=end;
            observed=Math.addExact(observed,e.counts().distinctValidOrders());
        }
        if(cursor.isBefore(requested.until())){gaps.add(new Interval(cursor,requested.until()));issues.add(Issue.GAP);}
        // No number for overlapping, partial, open or pre-native ranges. Zero remains unknown.
        var boundary=boundary(snapshot,mode,requested);
        Long count=issues.isEmpty()&&observed>0&&boundary!=Boundary.OPEN_INTERVAL&&
            boundary!=Boundary.NATIVE_BEFORE_TENANT_CREATION?observed:null;
        return new Assessment(snapshot,mode,requested,boundary,sorted,gaps,List.copyOf(issues),count,
            HistoricalCoverageService.Eligibility.BLOCKED);
    }
}
