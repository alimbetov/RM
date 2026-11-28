package kz.rm.intelligence;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.openxmlformats.schemas.drawingml.x2006.chart.*;
import java.io.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static kz.rm.intelligence.AuthorizedAnalyticalReadService.*;

/** D06C: populate the approved workbook, never recreate its charts or styles.
 * Internal renderer only; input comes from an already authorized report read. */
final class ReportTemplateRenderer {
    private static final String ROOT="/report-templates/";
    private static final Map<String,String> DIGESTS=Map.ofEntries(
        Map.entry("RM-R01","0ad586d9f1a2c64000f6223c037e5299376483cf948a19b55675430d0df52c4e"),
        Map.entry("RM-R02","b3318e86641f1be90ff751711a026bc594eb06a4e8d41c21aedef76d60ae8456"),
        Map.entry("RM-R03","07838f8167d45fdf4abc5b4e28e0dc4a1ea84e18ea85661ba308185c21b40d4e"),
        Map.entry("RM-R04","c8a43484c0864f7c96ef5289cb2d89b9de366936d32f04870b77b0ec0eef52df"),
        Map.entry("RM-R05","555c7c4fe111507b5c6456f500ff21d9bf7c900d49645e07ad8ca5d4a97588b5"),
        Map.entry("RM-R06","8ec2f7fb8c18e06f25a844676811a2787e1d843fd18513c24ea786c8a5cdadbd"),
        Map.entry("RM-R07","1dfddc48bef98cf1179b36cef3ed0acaecb3bcd2f2f5012a5c685e0fe192fc17"),
        Map.entry("RM-R08","18f608efaf963f8194265d7c5c25bb013637e81a927c35947fe6fa5be0a9a0c3"),
        Map.entry("RM-R09","2cc3679f4a2b0b5b13bc64233de5fdedf0164c5873d9d31f09b2f833c9a1b77f"),
        Map.entry("RM-R10","ba23056945cc919764646dbee51052ac97b74314cf187273daa83b1e304477b4"),
        Map.entry("RM-R11","dc626ca2ffc4ca04d50ad2642678e25bc774246bb05642d17dd79db917b3035a"),
        Map.entry("RM-R12","dac4742df7db8944293ade8c6e9c7cd87ce098b3dbf51efe348c09cd678df981")
    );
    private static final Pattern RANGE=Pattern.compile("^'([A-Za-z]+)'!\\$([A-Z]+)\\$(\\d+):\\$([A-Z]+)\\$(\\d+)$");
    private ReportTemplateRenderer(){}

    static byte[] renderBooked(String code,ReportSourceManifest manifest){
        if(!Set.of("RM-R01","RM-R02").contains(code))throw new IllegalArgumentException("BOOKED_TEMPLATE");
        var amount=ReportStatisticalView.select(manifest,Metric.BOOKED_AMOUNT,ReportStatisticalView.Range.M24);
        var count=ReportStatisticalView.select(manifest,Metric.BOOKED_ORDERS,ReportStatisticalView.Range.M24);
        if(amount.query().region()!=null||amount.query().group()!=null||amount.query().sku()!=null||
            !amount.query().warehouses().isEmpty()||count.query().group()!=null||count.query().sku()!=null||
            !count.query().warehouses().isEmpty())throw new IllegalArgumentException("REPORT_TEMPLATE_SCOPE");
        var values=amount.points();var orders=count.points();
        if(values.size()!=24||orders.size()!=24)throw new IllegalArgumentException("REPORT_TEMPLATE_WINDOW");
        String currency=manifest.accountingPolicy().currency(),unit="Million "+currency;
        try(var book=open(code);var out=new ByteArrayOutputStream()){
            clear(book);
            var d=book.getSheet("Dashboard");
            put(d,2,0,"PARTIAL / MISSING | "+manifest.tenant());
            put(d,3,0,"Scope: Tenant-wide booked; fulfilled not admitted");
            put(d,4,0,"Metric: BOOKED_AMOUNT / BOOKED_ORDERS");
            put(d,5,0,"Unit: "+unit+" | As of: "+manifest.observedAt());
            put(d,6,0,"Observed data only. Historical conclusions not admitted.");
            put(d,8,0,"FULFILLED_AMOUNT");put(d,8,3,"BOOKED_AMOUNT");put(d,8,6,"YOY_RATIO");
            put(d,9,3,million(values.getLast().value()));
            put(d,11,0,unit);put(d,11,3,unit);put(d,11,6,"ratio");
            var history=book.getSheet("History");
            var booked=book.getSheet("Booked");var fulfilled=book.getSheet("Dynamics");
            for(int i=0;i<24;i++){
                var p=values.get(i);var n=orders.get(i);
                if(!p.period().equals(n.period()))throw new IllegalStateException("REPORT_TEMPLATE_PERIOD");
                String month=month(p.period());
                put(history,i+1,0,month);put(history,i+1,1,million(p.value()));
                put(history,i+1,3,"BOOKED="+p.quality()+"; FULFILLED=MISSING");
                meta(book,"BOOKED_ORDERS "+month,exact(n.value())+" | "+n.quality());
                meta(book,"BOOKED_AMOUNT "+month,exact(p.value())+" "+currency+" | "+p.quality());
                if(i<12)continue;
                var prior=values.get(i-12);int row=i-11;
                for(var sheet:List.of(booked,fulfilled)){
                    put(sheet,row,0,month);put(sheet,row,5,month(prior.period()));
                }
                put(booked,row,1,million(p.value()));put(booked,row,2,million(prior.value()));
                // Reuse the existing mathematical kernel, never calculate a ratio in Excel.
                var cmp=DerivedMetricEngine.comparePeriods(prior.value(),prior.quality(),p.value(),p.quality());
                put(booked,row,3,million(cmp.change().value()));put(booked,row,4,cmp.growth().value());
                put(booked,row,6,"CURRENT="+p.quality()+"; PRIOR="+prior.quality()+"; DELTA="+cmp.change().quality());
                put(fulfilled,row,6,"MISSING: FULFILLED_AMOUNT_NOT_IN_SOURCE");
            }
            if(code.equals("RM-R01")){
                var s=book.getSheet("Summary");
                summary(s,1,"BOOKED_AMOUNT",million(values.getLast().value()),million(values.get(11).value()),unit,values.getLast().quality()+" / "+values.get(11).quality());
                summary(s,2,"FULFILLED_AMOUNT",null,null,unit,"MISSING");
                summary(s,3,"BOOKED_ORDERS",orders.getLast().value(),orders.get(11).value(),"COUNT",orders.getLast().quality()+" / "+orders.get(11).quality());
                summary(s,4,"CANCELLED_ORDERS",null,null,"COUNT","MISSING");
            }
            meta(book,"Report code",code);meta(book,"Report name",d.getRow(1).getCell(0).getStringCellValue());
            meta(book,"Template version","MOCK-0.1 / D06C");meta(book,"Tenant",manifest.tenant().toString());
            meta(book,"Data quality","PARTIAL / MISSING");meta(book,"Actual scope","Tenant-wide booked only");
            meta(book,"Metric code","BOOKED_AMOUNT / BOOKED_ORDERS");meta(book,"Unit / currency",unit);
            meta(book,"As of",manifest.observedAt().toString());meta(book,"Generated at",manifest.observedAt().toString());
            meta(book,"Mode","INTERNAL_DIAGNOSTIC");meta(book,"Visibility","NOT_PUBLISHED");
            meta(book,"Period bounds",values.getFirst().period().startInclusive()+" / "+values.getLast().period().endExclusive());
            meta(book,"Baseline bounds",values.getFirst().period().startInclusive()+" / "+values.get(11).period().endExclusive());
            meta(book,"Time zone",values.getLast().period().zoneId());meta(book,"Source revision",Long.toString(manifest.sourceRevision()));
            meta(book,"Source manifest SHA-256",manifest.sha256());meta(book,"Scope SHA-256",manifest.scopeDigest());
            meta(book,"Calendar version",Long.toString(values.getLast().period().calendarVersion()));
            meta(book,"Coverage","HISTORICAL_CONTINUITY_UNQUALIFIED");meta(book,"Limits","No fulfilled or unsupported metrics; no historical READY.");
            refreshCharts(book);
            book.write(out);byte[] bytes=StableReportPackage.canonical(out.toByteArray(),manifest.observedAt());
            if(bytes.length>2*1024*1024)throw new IllegalStateException("REPORT_OUTPUT_SIZE");
            return bytes;
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    /** These bindings use the exact existing table capacities and metrics.
     * Request scope remains the explicit authorized warehouse/SKU/currency. */
    static void requireWindow(String code,List<Query> qs,List<LocalDate> dates,
                              BusinessInterpretationEngine.Policy policy,BusinessAnomalyAssessment.Policy anomaly){
        Objects.requireNonNull(code);Objects.requireNonNull(qs);Objects.requireNonNull(dates);
        List<Metric> metrics;int n;PeriodResolver.Code period;
        switch(code){
            case "RM-R07" -> {metrics=List.of(Metric.STOCK_IN,Metric.STOCK_OUT);n=12;period=PeriodResolver.Code.M;}
            case "RM-R10" -> {metrics=List.of(Metric.FULFILLED_AMOUNT);n=6;period=PeriodResolver.Code.W;
                Objects.requireNonNull(policy);Objects.requireNonNull(anomaly);}
            case "RM-R11" -> {metrics=List.of(Metric.FULFILLED_PRODUCT_UNITS,Metric.STOCK_IN,Metric.STOCK_OUT);n=2;period=PeriodResolver.Code.W;
                Objects.requireNonNull(policy);}
            default -> throw new IllegalArgumentException("REPORT_WINDOW_TEMPLATE");
        }
        if(!qs.stream().map(Query::metric).toList().equals(metrics)||dates.size()!=n||
            qs.stream().anyMatch(q->q.period()!=period||q.warehouses().isEmpty()||!q.anchor().equals(dates.getLast())
                ||(code.equals("RM-R07")||code.equals("RM-R11"))&&q.sku()==null))
            throw new IllegalArgumentException("REPORT_TEMPLATE_WINDOW");
        for(int i=0;i<n;i++){
            var expected=period==PeriodResolver.Code.M?dates.getLast().minusMonths(n-1-i):dates.getLast().minusWeeks(n-1-i);
            if(!expected.equals(dates.get(i))||(period==PeriodResolver.Code.M?expected.getDayOfMonth()!=1:
                expected.getDayOfWeek()!=DayOfWeek.MONDAY))throw new IllegalArgumentException("REPORT_TEMPLATE_PERIODS");
        }
    }

    static byte[] renderWindow(String code,UUID tenant,UUID actor,List<Query> queries,List<LocalDate> dates,List<List<Result>> windows,
                              BusinessInterpretationEngine.Policy policy,BusinessAnomalyAssessment.Policy anomaly){
        if(windows.size()!=queries.size()||windows.isEmpty()||windows.getFirst().isEmpty())
            throw new IllegalArgumentException("REPORT_TEMPLATE_WINDOW");
        var base=windows.getFirst();var first=base.getFirst();var last=base.getLast();var query=queries.getFirst();
        var evidence=java.util.stream.IntStream.range(0,queries.size())
            .mapToObj(i->new BusinessConclusionService.Evidence(queries.get(i),windows.get(i))).toList();
        var guard=BusinessConclusionService.guard(evidence,dates);
        for(int i=0;i<windows.size();i++){
            var w=windows.get(i);var q=queries.get(i);
            if(w.size()!=base.size())throw new IllegalArgumentException("REPORT_TEMPLATE_WINDOW");
            for(int j=0;j<w.size();j++){
                var p=w.get(j);
                if(p.metric()!=q.metric()||p.methodVersion()!=1||!Objects.equals(p.currency(),q.currency())
                    ||!p.period().equals(base.get(j).period())||!first.observedAt().equals(p.observedAt())
                    ||first.sourceRevision()!=p.sourceRevision()||p.period().endExclusive().isAfter(p.observedAt())
                    ||(j>0&&!w.get(j-1).period().endExclusive().equals(p.period().startInclusive())))
                    throw new IllegalArgumentException("REPORT_TEMPLATE_EVIDENCE");
            }
        }
        try(var book=open(code);var out=new ByteArrayOutputStream()){
            clear(book);var dash=book.getSheet("Dashboard");
            String unit=first.currency()==null?first.unit():first.currency();
            String scope="Warehouses="+query.warehouses()+"; SKU="+Objects.toString(query.sku(),"ALL")+
                "; group="+Objects.toString(query.group(),"ALL")+"; region="+Objects.toString(query.region(),"ALL");
            put(dash,2,0,"OBSERVED / QUALITY PER ROW | "+tenant);put(dash,3,0,"Scope: "+scope);
            put(dash,4,0,"Metric: "+queries.stream().map(q->q.metric().name()).toList());
            put(dash,5,0,"Unit: "+unit+" | As of: "+first.observedAt());
            put(dash,6,0,"Historical conclusions require qualified evidence; flows are not availability.");
            meta(book,"Report code",code);meta(book,"Report name",dash.getRow(1).getCell(0).getStringCellValue());
            meta(book,"Template version","MOCK-0.1 / D06C");meta(book,"Tenant",tenant.toString());
            meta(book,"Requested scope",scope);meta(book,"Actual scope",scope);
            meta(book,"Metric code",queries.stream().map(q->q.metric().name()).toList().toString());
            meta(book,"Unit / currency",unit);meta(book,"Time zone",first.period().zoneId());
            meta(book,"Period bounds",first.period().startInclusive()+" / "+last.period().endExclusive());
            meta(book,"As of",first.observedAt().toString());meta(book,"Generated at",Instant.now().toString());
            meta(book,"Source revision",Long.toString(first.sourceRevision()));
            meta(book,"Calendar version",Long.toString(query.calendarVersion()));
            meta(book,"Mode","INTERNAL_DIAGNOSTIC");meta(book,"Visibility","NOT_PUBLISHED");
            meta(book,"Data quality",windows.stream().flatMap(List::stream).map(p->p.quality().name()).distinct().toList().toString());
            meta(book,"Coverage","HISTORICAL_CONTINUITY_UNQUALIFIED");meta(book,"Limits","No production decision admission.");
            for(var w:windows)for(var p:w)meta(book,p.metric().name()+" "+periodLabel(p.period()),
                exact(p.value())+" | "+p.quality()+" | "+p.qualityReason()+" | "+p.route()+" | "+
                Objects.toString(p.generation(),"-")+" | "+Objects.toString(p.proof(),"-"));
            if(code.equals("RM-R07")){
                var sheet=book.getSheet("Dynamics");var stockOut=windows.get(1);
                for(int i=0;i<12;i++){
                    var in=base.get(i);var exit=stockOut.get(i);
                    put(sheet,i+1,0,periodLabel(in.period()));put(sheet,i+1,1,in.value());put(sheet,i+1,2,exit.value());
                    put(sheet,i+1,3,"IN="+in.quality()+"; OUT="+exit.quality());
                }
                kpi(dash,0,"STOCK_IN",last.value(),unit);kpi(dash,3,"STOCK_OUT",stockOut.getLast().value(),unit);
                kpi(dash,6,"Period",periodLabel(last.period()),"");
            }else if(code.equals("RM-R10")){
                var sheet=book.getSheet("Dynamics");
                var result=guard==null?BusinessAnomalyAssessment.evaluate(points(tenant,actor,query,base),policy,anomaly):null;
                for(int i=0;i<6;i++){var p=base.get(i);put(sheet,i+1,0,periodLabel(p.period()));put(sheet,i+1,1,p.value());put(sheet,i+1,5,p.quality().name());}
                // A single preceding baseline is evidence for the final point only.
                // No fabricated rolling baseline or threshold series.
                boolean admitted=result!=null&&result.quality()==DataQuality.READY;
                if(admitted){
                    put(sheet,6,2,result.sma());
                    var band=BusinessAnomalyAssessment.band(result,anomaly);
                    put(sheet,6,3,band.lower());put(sheet,6,4,band.upper());
                }
                kpi(dash,0,"FULFILLED_AMOUNT",last.value(),unit);
                kpi(dash,3,"SMA_BASELINE",admitted?result.sma():null,unit);
                kpi(dash,6,"Deviation",admitted?result.deviation():null,unit);
                meta(book,"Anomaly result",guard!=null?"ABSTAINED / "+guard.code()+" / "+guard.quality():
                    result.pattern()+" / "+result.reason()+" / "+result.quality());
            }else if(code.equals("RM-R11")){
                var series=java.util.stream.IntStream.range(0,3)
                    .mapToObj(i->points(tenant,actor,queries.get(i),windows.get(i))).toList();
                var result=guard==null?BusinessFactorAssessment.evaluate(series.get(0),series.get(1),series.get(2),policy):null;
                var sheet=book.getSheet("Details");
                boolean admitted=result!=null&&result.quality()==DataQuality.READY;
                var deltas=admitted?Arrays.asList(result.fulfilledDelta(),result.stockInDelta(),result.stockOutDelta()):Collections.<BigDecimal>nCopies(3,null);
                var quality=guard!=null?guard.quality():result.quality();
                for(int i=0;i<3;i++){
                    var w=windows.get(i);var p=w.getFirst();var c=w.getLast();
                    put(sheet,i+1,0,c.metric().name());put(sheet,i+1,1,c.value());put(sheet,i+1,2,p.value());
                    if(admitted){
                        put(sheet,i+1,3,deltas.get(i));var comparison=DerivedMetricEngine.comparePeriods(p.value(),p.quality(),c.value(),c.quality());
                        put(sheet,i+1,4,comparison.growth().value());
                    }
                    put(sheet,i+1,5,"CURRENT="+c.quality()+"; PRIOR="+p.quality()+"; FACTOR="+quality);
                    kpi(dash,i==0?0:i==1?3:6,c.metric().name(),c.value(),c.unit());
                }
                meta(book,"Factor result",guard!=null?"ABSTAINED / "+guard.code()+" / "+guard.quality():
                    result.status()+" / "+result.reason()+" / "+result.quality());
            }else throw new IllegalArgumentException("REPORT_WINDOW_TEMPLATE");
            refreshCharts(book);book.write(out);
            byte[] bytes=out.toByteArray();if(bytes.length>2*1024*1024)throw new IllegalStateException("REPORT_OUTPUT_SIZE");
            return bytes;
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private static List<BusinessInterpretationEngine.Point> points(UUID tenant,UUID actor,Query q,List<Result> rows){
        return rows.stream().map(p->new BusinessInterpretationEngine.Point(
            new BusinessInterpretationEngine.Scope(tenant,actor,q.metric(),q.calendarVersion(),q.period(),q.region(),
                q.group(),q.sku(),q.warehouses(),q.currency(),p.unit(),p.methodVersion()),p)).toList();
    }
    private static String periodLabel(PeriodResolver.Period p){
        return p.startInclusive().atZone(ZoneId.of(p.zoneId())).toLocalDate()+" / "+
            p.endExclusive().atZone(ZoneId.of(p.zoneId())).toLocalDate();
    }
    private static void kpi(Sheet s,int col,String name,Object value,String unit){
        put(s,8,col,name);put(s,9,col,value);put(s,11,col,unit);
    }
    static byte[] renderOrders(ReportTemplateOwnerBindings.Snapshot snapshot,PeriodResolver.Period current,
                               PeriodResolver.Period prior,List<ReportTemplateOwnerBindings.StatusRow> rows){
        return ownerWorkbook("RM-R03",snapshot,"Tenant-wide order status events","COUNT","PARTIAL / MISSING",book->{
            var d=book.getSheet("Dashboard");var detail=book.getSheet("Details");
            for(int i=0;i<rows.size();i++){
                var p=rows.get(i);int row=i+1;
                put(detail,row,0,p.metric());put(detail,row,1,p.current());put(detail,row,2,p.prior());
                var c=DerivedMetricEngine.comparePeriods(p.prior(),p.priorQuality(),p.current(),p.currentQuality());
                put(detail,row,3,c.change().value());put(detail,row,4,c.growth().value());
                put(detail,row,5,"CURRENT="+p.currentQuality()+"; PRIOR="+p.priorQuality());
                meta(book,p.metric()+" exact",exact(p.current())+" / "+exact(p.prior()));
                if(i!=1)kpi(d,i==0?0:i==2?3:6,p.metric(),p.current(),"COUNT");
            }
            ownerPeriods(book,current,prior);meta(book,"Limits","Status event counts, not a cohort funnel; no qualified historical comparisons.");
        });
    }
    static byte[] renderPositions(ReportTemplateOwnerBindings.Snapshot snapshot,UUID warehouse,List<ReportTemplateOwnerBindings.Position> rows){
        return ownerWorkbook("RM-R06",snapshot,"Warehouse="+warehouse,"SKU_QUANTITY (per SKU)","LIVE POSITION",book->{
            var d=book.getSheet("Dashboard");var detail=book.getSheet("Details");
            for(int i=0;i<rows.size();i++){
                var p=rows.get(i);int row=i+1;String sku=p.sku().toString();
                put(detail,row,0,sku);put(detail,row,1,p.value());put(detail,row,2,p.quality().name());
                meta(book,"CURRENT_AVAILABLE "+sku,exact(p.value())+" | "+p.quality());
                if(i<3)kpi(d,i==0?0:i==1?3:6,sku,p.value(),"SKU_QUANTITY");
            }
            meta(book,"Warehouse",warehouse.toString());meta(book,"Coverage","LIVE_OWNER_POSITION_ONLY");
            meta(book,"Limits","Per-SKU quantities; no aggregation across different SKU units; no historical balances.");
        });
    }
    static byte[] renderDistribution(String code,ReportTemplateOwnerBindings.Snapshot snapshot,PeriodResolver.Period current,
                            PeriodResolver.Period prior,List<UUID> warehouses,String currency,List<ReportTemplateOwnerBindings.DistributionRow> rows){
        boolean products=code.equals("RM-R04");
        return ownerWorkbook(code,snapshot,"Assigned warehouses="+warehouses,
            products?currency:"Million "+currency,"PARTIAL / MISSING — observed source only",book->{
            var d=book.getSheet("Dashboard");var detail=book.getSheet("Details");
            if(products){
                // Historical completeness is not established. Keep the rank bands,
                // totals, counts and shares blank; show observed SKU amounts only.
                put(detail,0,3,"FULFILLED_AMOUNT "+currency);
                for(int i=0;i<rows.size();i++){
                    var p=rows.get(i);put(detail,i+1,0,p.key());put(detail,i+1,3,p.current());
                    put(detail,i+1,4,"PARTIAL: OBSERVED_NOT_RANKED");
                }
                put(book.getSheet("Groups"),1,6,"MISSING: QUALIFIED_POPULATION_REQUIRED");
                kpi(d,0,"FULFILLED_AMOUNT",null,currency);kpi(d,3,"Eligible SKU",null,"COUNT");kpi(d,6,"Rank bands",null,"COUNT");
                meta(book,"Limits","Observed SKU detail only. Full distribution, rank, total and share require complete authorized population.");
            }else{
                for(int i=0;i<rows.size();i++){
                    var p=rows.get(i);put(detail,i+1,0,p.key());put(detail,i+1,1,million(p.current()));
                    put(detail,i+1,2,million(p.prior()));put(detail,i+1,5,"PARTIAL / MISSING");
                    meta(book,"Region "+p.key(),exact(p.current())+" / "+exact(p.prior())+" "+currency);
                }
                kpi(d,0,"FULFILLED_AMOUNT",null,"Million "+currency);kpi(d,3,"PREVIOUS",null,"Million "+currency);kpi(d,6,"YOY_RATIO",null,"ratio");
                meta(book,"Limits","Observed fulfillment geography only; UNKNOWN retained; no growth or full distribution claim.");
            }
            ownerPeriods(book,current,prior);
            meta(book,"Warehouse",warehouses.toString());meta(book,"Metric code","FULFILLED_AMOUNT");
            meta(book,"Actual cutoff",current.endExclusive().isAfter(snapshot.asOf())?snapshot.asOf().toString():current.endExclusive().toString());
        });
    }
    static byte[] renderGated(String code,ReportTemplateOwnerBindings.Snapshot snapshot,String reason){
        return ownerWorkbook(code,snapshot,"No owner source admitted","NOT_AVAILABLE","MISSING / HOLD",book->{
            var d=book.getSheet("Dashboard");kpi(d,0,"DATA STATUS","MISSING","");kpi(d,3,"SOURCE","NOT_ADMITTED","");kpi(d,6,"ADMISSION","HOLD","");
            for(Sheet s:book){
                if(Set.of("Dashboard","Metadata").contains(s.getSheetName()))continue;
                for(Cell c:s.getRow(0))if(c.getCellType()==CellType.STRING){
                    String label=c.getStringCellValue();
                    if(label.startsWith("Mock "))c.setCellValue(label.substring(5));
                    if(label.equals("Quality"))put(s,1,c.getColumnIndex(),"MISSING: "+reason);
                }
            }
            meta(book,"Coverage","NOT_ADMITTED");meta(book,"Source revision","NOT_AVAILABLE");
            meta(book,"Limits",reason+"; no actual or synthetic metric values.");
        });
    }
    private static void ownerPeriods(XSSFWorkbook book,PeriodResolver.Period current,PeriodResolver.Period prior){
        meta(book,"Period bounds",current.startInclusive()+" / "+current.endExclusive());
        if(prior!=null)meta(book,"Baseline bounds",prior.startInclusive()+" / "+prior.endExclusive());
        meta(book,"Time zone",current.zoneId());meta(book,"Calendar version",Long.toString(current.calendarVersion()));
    }
    private static byte[] ownerWorkbook(String code,ReportTemplateOwnerBindings.Snapshot snapshot,String scope,
                                  String unit,String quality,java.util.function.Consumer<XSSFWorkbook> binding){
        try(var book=open(code);var out=new ByteArrayOutputStream()){
            clear(book);var d=book.getSheet("Dashboard");
            put(d,2,0,quality+" | "+snapshot.tenant());put(d,3,0,"Scope: "+scope);
            put(d,5,0,"Unit: "+unit+" | As of: "+snapshot.asOf());
            put(d,6,0,"Internal diagnostic; no production decision admission.");
            meta(book,"Report code",code);meta(book,"Report name",d.getRow(1).getCell(0).getStringCellValue());
            meta(book,"Template version","MOCK-0.1 / D06C");meta(book,"Tenant",snapshot.tenant().toString());
            meta(book,"Data quality",quality);meta(book,"Requested scope",scope);meta(book,"Actual scope",scope);
            meta(book,"Unit / currency",unit);meta(book,"As of",snapshot.asOf().toString());
            meta(book,"Generated at",Instant.now().toString());meta(book,"Mode","INTERNAL_DIAGNOSTIC");
            meta(book,"Visibility","NOT_PUBLISHED");meta(book,"Source revision",Long.toString(snapshot.revision()));
            meta(book,"Coverage","HISTORICAL_CONTINUITY_UNQUALIFIED");
            binding.accept(book);refreshCharts(book);book.write(out);
            byte[] bytes=out.toByteArray();if(bytes.length>2*1024*1024)throw new IllegalStateException("REPORT_OUTPUT_SIZE");
            return bytes;
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    static XSSFWorkbook open(String code)throws IOException{
        if(code==null||!code.matches("RM-R(0[1-9]|1[0-2])"))throw new IllegalArgumentException("REPORT_TEMPLATE_CODE");
        try(var in=Objects.requireNonNull(ReportTemplateRenderer.class.getResourceAsStream(ROOT+code+".xlsx"))){
            byte[] bytes=in.readNBytes(2*1024*1024+1);
            if(bytes.length>2*1024*1024)throw new IllegalStateException("REPORT_TEMPLATE_SIZE");
            try{
                String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                if(!actual.equals(DIGESTS.get(code)))throw new IllegalStateException("REPORT_TEMPLATE_DIGEST");
            }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
            return new XSSFWorkbook(new ByteArrayInputStream(bytes));
        }
    }
    private static void clear(XSSFWorkbook b){
        if(!b.getExternalLinksTable().isEmpty())throw new IllegalStateException("REPORT_TEMPLATE_EXTERNAL_LINK");
        for(int i=0;i<b.getNumberOfSheets();i++){
            if(b.isSheetHidden(i)||b.isSheetVeryHidden(i))throw new IllegalStateException("REPORT_TEMPLATE_HIDDEN_SHEET");
            Sheet sheet=b.getSheetAt(i);String name=sheet.getSheetName();
            for(Row row:sheet)for(Cell cell:row){
                if(cell.getCellType()==CellType.FORMULA)throw new IllegalStateException("REPORT_TEMPLATE_FORMULA");
                boolean keep=name.equals("Dashboard")?row.getRowNum()<2:
                    name.equals("Metadata")?row.getRowNum()==0||cell.getColumnIndex()==0:row.getRowNum()==0;
                if(!keep)cell.setBlank();
            }
        }
    }
    private static void summary(Sheet s,int r,String metric,BigDecimal current,BigDecimal prior,String unit,String quality){
        put(s,r,0,metric);put(s,r,1,current);put(s,r,2,prior);put(s,r,3,unit);put(s,r,4,quality);
    }
    private static void meta(XSSFWorkbook b,String key,String value){
        Sheet s=b.getSheet("Metadata");
        for(Row row:s){var c=row.getCell(0);if(c!=null&&c.getCellType()==CellType.STRING&&key.equals(c.getStringCellValue())){
            put(s,row.getRowNum(),1,value);return;
        }}
        int r=s.getLastRowNum()+1;if(r>100)throw new IllegalStateException("REPORT_METADATA_BUDGET");
        var row=s.createRow(r);var sample=s.getRow(1);
        row.createCell(0).setCellStyle(sample.getCell(0).getCellStyle());
        row.createCell(1).setCellStyle(sample.getCell(1).getCellStyle());
        put(s,r,0,key);put(s,r,1,value);
    }
    private static String month(PeriodResolver.Period p){return YearMonth.from(p.startInclusive().atZone(ZoneId.of(p.zoneId()))).toString();}
    private static BigDecimal million(BigDecimal x){return x==null?null:x.movePointLeft(6);}
    private static String exact(BigDecimal x){return x==null?"MISSING":ReportDatasetDigest.decimal(x);}
    private static void put(Sheet s,int row,int col,Object value){
        var cell=Objects.requireNonNull(s.getRow(row),"REPORT_TEMPLATE_ROW").getCell(col,Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
        if(value==null)cell.setBlank();
        else if(value instanceof String t)cell.setCellValue(t);
        else if(value instanceof BigDecimal d){
            double number=d.doubleValue();
            if(d.stripTrailingZeros().precision()>15||!Double.isFinite(number)||number==0&&d.signum()!=0)cell.setCellValue(exact(d));
            else cell.setCellValue(number);
        }else throw new IllegalArgumentException("REPORT_TEMPLATE_CELL");
    }
    private static void refreshCharts(XSSFWorkbook b){
        // Keep the existing table ranges and columns. When a binding changes
        // a header (currency or removal of a mock label), synchronize OOXML
        // table metadata with the actual header row before writing the file.
        for(int i=0;i<b.getNumberOfSheets();i++)
            for(var table:b.getSheetAt(i).getTables())table.updateHeaders();
        for(int i=0;i<b.getNumberOfSheets();i++){
            var drawing=b.getSheetAt(i).getDrawingPatriarch();if(drawing==null)continue;
            for(var chart:drawing.getCharts()){
                var ct=chart.getCTChartSpace().getChart();
                var plot=ct.getPlotArea();int series=0;
                for(var line:plot.getLineChartList())for(var s:line.getSerList()){refresh(b,s.getCat(),s.getVal());series++;}
                for(var bar:plot.getBarChartList())for(var s:bar.getSerList()){refresh(b,s.getCat(),s.getVal());series++;}
                if(series==0)throw new IllegalStateException("REPORT_CHART_TYPE");
                if(ct.isSetDispBlanksAs())ct.getDispBlanksAs().setVal(STDispBlanksAs.GAP);
                else ct.addNewDispBlanksAs().setVal(STDispBlanksAs.GAP);
            }
        }
    }
    private static void refresh(XSSFWorkbook b,CTAxDataSource cat,CTNumDataSource val){
        if(cat==null||val==null||!cat.isSetStrRef()||!val.isSetNumRef())throw new IllegalStateException("REPORT_CHART_REFERENCE");
        var sr=cat.getStrRef();var nr=val.getNumRef();
        var sc=sr.isSetStrCache()?sr.getStrCache():sr.addNewStrCache();
        var nc=nr.isSetNumCache()?nr.getNumCache():nr.addNewNumCache();
        var labels=cells(b,sr.getF());var values=cells(b,nr.getF());
        if(labels.size()!=values.size())throw new IllegalStateException("REPORT_CHART_RANGE");
        while(sc.sizeOfPtArray()>0)sc.removePt(0);
        while(nc.sizeOfPtArray()>0)nc.removePt(0);
        (sc.isSetPtCount()?sc.getPtCount():sc.addNewPtCount()).setVal(labels.size());
        (nc.isSetPtCount()?nc.getPtCount():nc.addNewPtCount()).setVal(values.size());
        for(int i=0;i<labels.size();i++){
            var l=labels.get(i);var v=values.get(i);
            if(l!=null&&l.getCellType()==CellType.STRING){var p=sc.addNewPt();p.setIdx(i);p.setV(l.getStringCellValue());}
            if(v!=null&&v.getCellType()==CellType.NUMERIC){var p=nc.addNewPt();p.setIdx(i);p.setV(Double.toString(v.getNumericCellValue()));}
        }
    }
    private static List<Cell> cells(XSSFWorkbook b,String f){
        var m=RANGE.matcher(f);
        if(!m.matches()||!m.group(2).equals(m.group(4)))throw new IllegalStateException("REPORT_CHART_RANGE");
        Sheet s=b.getSheet(m.group(1));int first=Integer.parseInt(m.group(3)),last=Integer.parseInt(m.group(5));
        if(s==null||first<2||last<first||last>1001||last-1>s.getLastRowNum())throw new IllegalStateException("REPORT_CHART_RANGE");
        int col=CellReference.convertColStringToIndex(m.group(2));var result=new ArrayList<Cell>();
        for(int r=first-1;r<last;r++)result.add(s.getRow(r).getCell(col));
        return result;
    }
}
