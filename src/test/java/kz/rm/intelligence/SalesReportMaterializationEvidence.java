package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL owner-side pilot; empty tenant B must never inherit tenant A facts. */
public final class SalesReportMaterializationEvidence {
    private final SalesReportMaterializer materializer;
    private final AuthorizedAnalyticalReadService reader;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager manager;
    private final ApplicationContext context;
    public SalesReportMaterializationEvidence(ApplicationContext context,JdbcTemplate jdbc,PlatformTransactionManager manager){
        this.jdbc=jdbc;this.manager=manager;this.context=context;
        reader=context.getBean(AuthorizedAnalyticalReadService.class);
        materializer=new SalesReportMaterializer(reader,jdbc,manager);
    }
    public void scopedTemplates(VerifiedActor actor,VerifiedActor foreign,UUID tenant,long calendar,UUID warehouse,UUID sku,LocalDate today){
        var last=today.withDayOfMonth(1).minusMonths(1);
        var lastWeek=today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusWeeks(1);
        var money=java.math.BigDecimal.ONE;
        var policy=new BusinessInterpretationEngine.Policy(1,money,java.math.BigDecimal.ZERO);
        var anomaly=new BusinessAnomalyAssessment.Policy(1,new java.math.BigDecimal("3"),new java.math.BigDecimal("0.5"));
        var monthly=java.util.stream.IntStream.range(0,12).mapToObj(i->last.minusMonths(11-i)).toList();
        var weeks=java.util.stream.IntStream.range(0,6).mapToObj(i->lastWeek.minusWeeks(5-i)).toList();
        var pair=java.util.List.of(lastWeek.minusWeeks(1),lastWeek);
        var q7=java.util.List.of(
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.STOCK_IN,calendar,PeriodResolver.Code.M,last,null,null,sku,java.util.List.of(warehouse),null),
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.STOCK_OUT,calendar,PeriodResolver.Code.M,last,null,null,sku,java.util.List.of(warehouse),null));
        var q10=java.util.List.of(new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.FULFILLED_AMOUNT,calendar,PeriodResolver.Code.W,lastWeek,null,null,null,java.util.List.of(warehouse),"KZT"));
        var q11=java.util.List.of(
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.FULFILLED_PRODUCT_UNITS,calendar,PeriodResolver.Code.W,lastWeek,null,null,sku,java.util.List.of(warehouse),null),
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.STOCK_IN,calendar,PeriodResolver.Code.W,lastWeek,null,null,sku,java.util.List.of(warehouse),null),
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.STOCK_OUT,calendar,PeriodResolver.Code.W,lastWeek,null,null,sku,java.util.List.of(warehouse),null));
        for(var code:java.util.List.of("RM-R07","RM-R10","RM-R11")){
            var queries=code.equals("RM-R07")?q7:code.equals("RM-R10")?q10:q11;
            var dates=code.equals("RM-R07")?monthly:code.equals("RM-R10")?weeks:pair;
            var bytes=reader.renderScopedTemplate(actor,tenant,code,queries,dates,policy,anomaly);
            var expected=reader.readMetricWindows(actor,tenant,queries,dates);
            try(var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))){
                assertThat(book.getSheet("Metadata").getRow(4).getCell(1).getStringCellValue()).isEqualTo(tenant.toString());
                assertThat(book.getSheet("Dashboard").getDrawingPatriarch().getCharts()).hasSize(1);
                var sheet=book.getSheet(code.equals("RM-R11")?"Details":"Dynamics");
                if(code.equals("RM-R11")){
                    for(int i=0;i<3;i++){
                        assertTemplateValue(sheet.getRow(i+1).getCell(1),expected.get(i).getLast().value());
                        assertTemplateValue(sheet.getRow(i+1).getCell(2),expected.get(i).getFirst().value());
                        assertThat(sheet.getRow(i+1).getCell(3).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                    }
                }else for(int i=0;i<dates.size();i++){
                    assertTemplateValue(sheet.getRow(i+1).getCell(1),expected.getFirst().get(i).value());
                    if(code.equals("RM-R07"))assertTemplateValue(sheet.getRow(i+1).getCell(2),expected.get(1).get(i).value());
                    else for(int col=2;col<=4;col++)
                        assertThat(sheet.getRow(i+1).getCell(col).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                }
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            ReportTemplateJourneyEvidence.record(code,tenant,bytes);
            assertThatThrownBy(()->reader.renderScopedTemplate(foreign,tenant,code,queries,dates,policy,anomaly)).isInstanceOf(SecurityException.class);
        }
        System.out.println("RM_D06C_SCOPED_TEMPLATE_SOURCE_ORACLE_PASS workbooks=3");
    }
    public void ownerTemplates(VerifiedActor actor,VerifiedActor foreign,UUID tenant,long calendar,UUID warehouse,UUID sku,LocalDate today){
        var service=context.getBean(ReportTemplateOwnerBindings.class);
        var metric=context.getBean(MetricQueryService.class);
        var month=java.time.YearMonth.from(today).minusMonths(1);
        var bytes=service.orders(actor,tenant,calendar,month);
        try(var book=readOwnerWorkbook(bytes,tenant)){
            var sheet=book.getSheet("Details");
            var expected=metric.calculate(actor,tenant,new MetricQuery(MetricRegistry.Code.PLACED_ORDERS,1,
                new MetricSelection.OrderPeriod(new MetricSelection.PeriodChoice(PeriodResolver.Code.M,month.atDay(1),calendar))));
            assertThat(expected.cells()).hasSize(1);
            assertTemplateValue(sheet.getRow(1).getCell(1),expected.cells().getFirst().value());
            assertThat(sheet.getRow(1).getCell(3).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            assertThat(sheet.getRow(1).getCell(5).getStringCellValue()).contains("PARTIAL");
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        ReportTemplateJourneyEvidence.record("RM-R03",tenant,bytes);
        bytes=service.positions(actor,tenant,warehouse,java.util.List.of(sku));
        try(var book=readOwnerWorkbook(bytes,tenant)){
            var expected=metric.calculate(actor,tenant,new MetricQuery(MetricRegistry.Code.CURRENT_AVAILABLE,1,
                new MetricSelection.LivePosition(warehouse,sku)));
            assertThat(expected.cells()).hasSize(1);
            var row=book.getSheet("Details").getRow(1);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo(sku.toString());
            assertTemplateValue(row.getCell(1),expected.cells().getFirst().value());
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("READY");
        }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
        ReportTemplateJourneyEvidence.record("RM-R06",tenant,bytes);
        for(var code:java.util.List.of("RM-R04","RM-R05")){
            bytes=service.distribution(actor,tenant,code,calendar,PeriodResolver.Code.D,today,java.util.List.of(warehouse),"KZT");
            try(var book=readOwnerWorkbook(bytes,tenant)){
                var detail=book.getSheet("Details");
                var from=java.sql.Timestamp.from(today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
                String key=code.equals("RM-R04")?"sku_id::text":"coalesce(region_code,'UNKNOWN')";
                var expected=jdbc.query("""
                    SELECT %s,sum(amount) FROM crm.int_l2_expected_source
                     WHERE tenant_id=? AND stream_code='FULFILLED' AND currency='KZT' AND warehouse_id=?
                       AND event_at>=? AND event_at<transaction_timestamp()
                     GROUP BY %s ORDER BY 1
                    """.formatted(key,key),(rs,n)->new Object[]{rs.getString(1),rs.getBigDecimal(2)},tenant,warehouse,from);
                assertThat(expected).isNotEmpty();
                for(int i=0;i<expected.size();i++){
                    var row=detail.getRow(i+1);var p=expected.get(i);
                    assertThat(row.getCell(0).getStringCellValue()).isEqualTo(p[0]);
                    var amount=(java.math.BigDecimal)p[1];
                    if(code.equals("RM-R04")){
                        assertTemplateValue(row.getCell(3),amount);
                        assertThat(row.getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                        assertThat(row.getCell(2).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                    }else{
                        assertTemplateValue(row.getCell(1),amount.movePointLeft(6));
                        assertThat(row.getCell(3).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
                    }
                }
                if(code.equals("RM-R04"))assertThat(book.getSheet("Groups").getRow(1).getCell(1).getCellType())
                    .isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            ReportTemplateJourneyEvidence.record(code,tenant,bytes);
            assertThatThrownBy(()->service.distribution(foreign,tenant,code,calendar,PeriodResolver.Code.D,today,
                java.util.List.of(warehouse),"KZT")).isInstanceOf(SecurityException.class);
        }
        for(var code:java.util.List.of("RM-R08","RM-R09","RM-R12")){
            bytes=service.gated(actor,tenant,code);
            try(var book=readOwnerWorkbook(bytes,tenant)){
                for(var sheet:book)if(!java.util.Set.of("Dashboard","Metadata").contains(sheet.getSheetName()))
                    for(var row:sheet)if(row.getRowNum()>0)for(var c:row)
                        assertThat(c.getCellType()).isNotEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
                var charts=book.getSheet("Dashboard").getDrawingPatriarch().getCharts();
                for(var chart:charts){
                    var plot=chart.getCTChartSpace().getChart().getPlotArea();
                    for(var line:plot.getLineChartList())for(var series:line.getSerList())
                        assertThat(series.getVal().getNumRef().getNumCache().sizeOfPtArray()).isZero();
                    for(var bar:plot.getBarChartList())for(var series:bar.getSerList())
                        assertThat(series.getVal().getNumRef().getNumCache().sizeOfPtArray()).isZero();
                }
                assertThat(book.getSheet("Dashboard").getRow(9).getCell(6).getStringCellValue()).isEqualTo("HOLD");
            }catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            ReportTemplateJourneyEvidence.record(code,tenant,bytes);
            assertThatThrownBy(()->service.gated(foreign,tenant,code)).isInstanceOf(SecurityException.class);
        }
        assertThatThrownBy(()->service.orders(foreign,tenant,calendar,month)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.positions(foreign,tenant,warehouse,java.util.List.of(sku))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->service.positions(actor,tenant,warehouse,java.util.List.of(sku,sku))).isInstanceOf(IllegalArgumentException.class);
        ReportTemplateJourneyEvidence.verifyCatalog(tenant);
        System.out.println("RM_D06C_OWNER_TEMPLATE_ORACLE_PASS owner=4 gated=3");
    }
    private static org.apache.poi.xssf.usermodel.XSSFWorkbook readOwnerWorkbook(byte[] bytes,UUID tenant)throws java.io.IOException{
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))){
            for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry())if(e.getName().endsWith(".xml")){
                var xml=new String(zip.readNBytes(4*1024*1024+1),java.nio.charset.StandardCharsets.UTF_8);
                assertThat(xml).doesNotContain("TENANT-A","SYNTHETIC MOCKUP","SYNTHETIC_NOT_QUALIFIED","DESIGN MOCK","-DEMO");
            }
        }
        var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));
        assertThat(book.getSheet("Metadata").getRow(4).getCell(1).getStringCellValue()).isEqualTo(tenant.toString());
        assertThat(book.getSheet("Dashboard").getDrawingPatriarch().getCharts()).hasSize(1);
        return book;
    }
    private static void assertTemplateValue(org.apache.poi.ss.usermodel.Cell cell,java.math.BigDecimal expected){
        if(expected==null)assertThat(cell.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.BLANK);
        else if(expected.stripTrailingZeros().precision()>15)assertThat(cell.getStringCellValue()).isEqualTo(ReportDatasetDigest.decimal(expected));
        else assertThat(cell.getNumericCellValue()).isEqualTo(expected.doubleValue());
    }
    public void denied(VerifiedActor actor,UUID tenant,long calendar,LocalDate last){
        assertThatThrownBy(()->materializer.materialize(actor,tenant,calendar,last,"KZT"))
            .isInstanceOf(SecurityException.class);
    }
    public void deniedReplay(VerifiedActor actor,UUID tenant,long calendar,LocalDate last){
        assertThatThrownBy(()->materializer.materialize(actor,tenant,calendar,last,"KZT","empty-"+tenant))
            .isInstanceOf(SecurityException.class);
    }
    public void privateReportFile(VerifiedActor actor,VerifiedActor foreign,UUID tenant,long calendar,LocalDate last,
                                  Runnable grantAttach,Runnable revokeAttach){
        String key="p41a-"+UUID.randomUUID();
        var report=materializer.prepareFile(actor,tenant,calendar,last,key);
        try{kz.rm.files.ReportUploadQualificationEvidence.verify(context,jdbc,manager,actor,foreign,report,
            ()->new SalesReportMaterializer(reader,jdbc,manager).prepareFile(actor,tenant,calendar,last,key),grantAttach,revokeAttach);}
        catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
    }
    public void emptyTenant(VerifiedActor actor,UUID tenant,UUID foreign,long calendar,LocalDate last){
        String key="empty-"+tenant;
        var r=materializer.materializeAccounting(actor,tenant,calendar,last,key);
        assertThat(materializer.materializeAccounting(actor,tenant,calendar,last,key)).isEqualTo(r);
        assertThatThrownBy(()->materializer.materialize(actor,tenant,calendar,last,"USD",key))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REPORT_ACCOUNTING_CURRENCY_MISMATCH");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_source_evidence
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
             AND encode(sha256(scope_bytes),'hex')=? AND encode(sha256(source_bytes),'hex')=?
             AND substring(source_bytes FROM 1 FOR 18)=convert_to('15:RM-ARS-SOURCE-3','UTF8')
            """,Long.class,tenant,r.dataset(),r.stagingDate(),r.scopeDigest(),r.sourceDigest())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_request_attempt l JOIN reporting.int_report_instance i
             USING(tenant_id,instance_id) WHERE l.tenant_id=? AND i.idempotency_key=? AND l.selected_at IS NOT NULL
            """,Long.class,tenant,key)).isEqualTo(1L);
        assertBatchParity(actor,tenant,calendar,last,"KZT");
        assertThat(r.tenant()).isEqualTo(tenant);
        assertThat(r.expected()).isEqualTo(r.observed());
        assertThat(r.observed().rows()).isEqualTo(48);
        assertThat(jdbc.queryForObject("""
            SELECT build_state FROM reporting.int_report_dataset_attempt
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
            """,String.class,tenant,r.dataset(),r.stagingDate())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_dataset
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
            AND metric_value IS NULL AND quality_code='MISSING'
            AND ((metric_code='BOOKED_AMOUNT' AND currency='KZT' AND unit_code='CURRENCY')
              OR (metric_code='BOOKED_ORDERS' AND currency IS NULL AND unit_code='COUNT'))
            """,Long.class,tenant,r.dataset(),r.stagingDate())).isEqualTo(48L);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_dataset
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
            """,Long.class,foreign,r.dataset(),r.stagingDate())).isZero();
        for(String metric:java.util.List.of("BOOKED_AMOUNT","BOOKED_ORDERS"))
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reporting.int_report_dataset
                WHERE tenant_id=? AND dataset_id=? AND staging_date=? AND metric_code=?
                """,Long.class,tenant,r.dataset(),r.stagingDate(),metric)).isEqualTo(24L);
    }
    /** Synthetic legacy history, created after the original owner/projection assertions.
     * Never modifies an existing order, event or canonical fact. */
    public void historicalAndConcurrent(VerifiedActor actor,UUID tenant,long calendar,
            VerifiedActor otherActor,UUID other,long otherCalendar,LocalDate last,UUID template){
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
            historicalOrder(tenant,template,last.minusMonths(1),"KZT","100","125");
            historicalOrder(tenant,template,last,"KZT","150");
            historicalOrder(tenant,template,last,"KZT","50","100");
        });
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try{
            var a=pool.submit(()->{await(start);return materializer.materialize(actor,tenant,calendar,last);});
            var b=pool.submit(()->{await(start);return materializer.materialize(otherActor,other,otherCalendar,last);});
            start.countDown();
            var ra=a.get(150,java.util.concurrent.TimeUnit.SECONDS);
            var rb=b.get(150,java.util.concurrent.TimeUnit.SECONDS);
            assertReceipt(ra,tenant,4);
            assertReceipt(rb,other,0);
            assertThat(ra.dataset()).isNotEqualTo(rb.dataset());
            assertThat(ra.scopeDigest()).isNotEqualTo(rb.scopeDigest());
            assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM reporting.int_report_dataset
                WHERE tenant_id=? AND dataset_id=? AND staging_date=?
                """,Long.class,other,ra.dataset(),ra.stagingDate())).isZero();
            assertMonth(ra,last.minusMonths(1),"KZT","225","1");
            assertMonth(ra,last,"KZT","300","2");
            mixedAccountingRoute(actor,tenant,calendar,last,template);
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->
                historicalOrder(tenant,template,last.minusMonths(1),"USD","20"));
            assertThatThrownBy(()->materializer.materialize(actor,tenant,calendar,last))
                .isInstanceOf(IllegalStateException.class).hasMessage("REPORT_ACCOUNTING_FX_EVIDENCE_REQUIRED");
            assertThatThrownBy(()->materializer.materialize(actor,tenant,calendar,last,"USD"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("REPORT_ACCOUNTING_CURRENCY_MISMATCH");
            // Raw currency-specific diagnostic parity remains; not a standard USD report.
            assertBatchParity(actor,tenant,calendar,last,"KZT");
            assertBatchParity(actor,tenant,calendar,last,"USD");
            assertBatchParity(otherActor,other,otherCalendar,last,"KZT");
            System.out.println("RM_R02_HISTORICAL_CURRENCY_CONCURRENT_ORACLE_PASS");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
        catch(java.util.concurrent.ExecutionException|java.util.concurrent.TimeoutException e){throw new AssertionError(e);}
        finally{pool.shutdownNow();}
    }
    /** Real activated monthly generation inside the 24M report window.
     * The previous month has canonical BOOKED facts; the latest month remains L0.
     * A later source commit must invalidate the proof and restore exact L0 parity. */
    private void mixedAccountingRoute(VerifiedActor actor,UUID tenant,long calendar,LocalDate last,UUID template){
        var from=last.minusMonths(1);var until=from.plusMonths(1);
        var gen=UUID.randomUUID();
        var l1=context.getBean(L1ProjectionService.class);
        var l2=context.getBean(L2ProjectionService.class);
        var l3=context.getBean(L3RollupService.class);
        var coverage=context.getBean(MultidimensionalCoverageService.class);
        var generation=l1.open(tenant,gen,calendar,from,until);
        l1.advance(tenant,gen,500);l2.open(tenant,gen);l2.advance(tenant,gen,500);
        for(var code:PeriodResolver.Code.values()){
            LocalDate cursor=from;
            while(cursor.isBefore(until)){
                var interval=PeriodResolver.resolve(code,cursor,new PeriodResolver.Policy(generation.zone(),calendar));
                LocalDate a=interval.startInclusive().atZone(generation.zone()).toLocalDate();
                LocalDate b=interval.endExclusive().atZone(generation.zone()).toLocalDate();
                if(!a.isBefore(from)&&!b.isAfter(until))l3.refresh(tenant,gen,code,cursor);
                cursor=b;
            }
        }
        var proof=coverage.prove(tenant,gen);
        assertThat(proof.parity()).isTrue();
        UUID previous=jdbc.query("SELECT generation_id FROM crm.int_active_generation WHERE tenant_id=? AND projection_code='L3'",
            rs->rs.next()?rs.getObject(1,UUID.class):null,tenant);
        var active=coverage.activate(tenant,gen,proof.id(),previous);
        assertThat(coverage.status(tenant,gen,active.activeProof())).isEqualTo(MultidimensionalCoverageService.Status.CURRENT);
        var accounting=new org.springframework.transaction.support.TransactionTemplate(manager)
            .execute(status->reader.admitAccountingPolicy(actor,tenant));
        var queries=java.util.List.of(
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.BOOKED_AMOUNT,calendar,
                PeriodResolver.Code.M,last,null,null,null,java.util.List.of(),accounting.currency()),
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.BOOKED_ORDERS,calendar,
                PeriodResolver.Code.M,last,null,null,null,java.util.List.of(),null));
        var counter=new CountingJdbc(jdbc);
        var countedL3=new CountingL3(counter,manager,context);
        var instrumented=new AuthorizedAnalyticalReadService(counter,manager,countedL3,coverage);
        var report=instrumented.readAccountingReportMonths(actor,tenant,queries,accounting);
        assertThat(counter.l0).isEqualTo(2);
        assertThat(countedL3.calls).isEqualTo(2);
        assertStatisticalView(report,tenant,"225");
        ReportTemplateJourneyEvidence.qualify(report,"225");
        var diagnostic=context.getBean(NativeBookedReportEvidenceService.class).inspect(actor,tenant,calendar,
            java.time.YearMonth.from(last),java.time.YearMonth.from(from));
        assertThat(diagnostic.state()).isEqualTo(NativeBookedReportEvidenceService.State.SOURCE_NOT_QUALIFIED);
        assertThat(diagnostic.month().source().state()).isEqualTo(NativeBookedClosureService.State.CAPTURE_INACTIVE);
        assertThat(diagnostic.population()).isNull();assertThat(diagnostic.admitted()).isFalse();
        assertThat(diagnostic.manifest().sourceRevision()).isEqualTo(report.sourceRevision());
        assertThat(diagnostic.manifest().scopeDigest()).isEqualTo(report.scopeDigest());
        assertStatisticalView(diagnostic.manifest(),tenant,"225");
        assertThat(diagnostic.series(AuthorizedAnalyticalReadService.Metric.BOOKED_AMOUNT,ReportStatisticalView.Range.M3)
            .comparisons().getFirst().result().change().quality()).isEqualTo(DataQuality.PARTIAL);
        assertThatThrownBy(()->context.getBean(NativeBookedReportEvidenceService.class).inspect(actor,tenant,calendar,
            java.time.YearMonth.from(last),java.time.YearMonth.from(last).minusMonths(24)))
            .hasMessage("NATIVE_REPORT_TARGET");
        System.out.println("RM_ARS_P2_NATIVE_REPORT_AUTHORITY_HOLD_ORACLE_PASS");
        for(var window:report.windows()){
            assertThat(window).hasSize(24);
            assertThat(window.stream().filter(x->x.route()==AuthorizedAnalyticalReadService.Route.L3).toList())
                .singleElement().satisfies(x->{
                    assertThat(x.generation()).isEqualTo(gen);
                    assertThat(x.proof()).isEqualTo(active.activeProof());
                    assertThat(x.period().startInclusive().atZone(generation.zone()).toLocalDate()).isEqualTo(from);
                    assertThat(x.value()).isEqualByComparingTo(x.metric()==AuthorizedAnalyticalReadService.Metric.BOOKED_AMOUNT?"225":"1");
                    assertThat(x.quality()).isEqualTo(DataQuality.PARTIAL);
                });
            assertThat(window.getLast().route()).isEqualTo(AuthorizedAnalyticalReadService.Route.L0);
            assertThat(window.getLast().value()).isNotNull();
        }
        assertBatchParity(actor,tenant,calendar,last,accounting.currency());
        System.out.println("RM_R02_REAL_MIXED_ROUTE_ORACLE_PASS l0=2 l3=2 amount=225 orders=1");
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->
            historicalOrder(tenant,template,from,accounting.currency(),"25"));
        assertThat(coverage.status(tenant,gen,active.activeProof())).isEqualTo(MultidimensionalCoverageService.Status.STALE);
        counter.l0=0;countedL3.calls=0;
        var late=instrumented.readAccountingReportMonths(actor,tenant,queries,accounting);
        assertThat(counter.l0).isEqualTo(2);assertThat(countedL3.calls).isZero();
        for(var window:late.windows()){
            var point=window.get(22);
            assertThat(point.route()).isEqualTo(AuthorizedAnalyticalReadService.Route.L0);
            assertThat(point.reason()).isEqualTo("PROOF_STALE");
            assertThat(point.value()).isEqualByComparingTo(point.metric()==AuthorizedAnalyticalReadService.Metric.BOOKED_AMOUNT?"250":"2");
            assertThat(point.quality()).isEqualTo(DataQuality.PARTIAL);
        }
        assertBatchParity(actor,tenant,calendar,last,accounting.currency());
        assertThat(late.sha256()).isNotEqualTo(report.sha256());
        assertStatisticalView(late,tenant,"250");
        System.out.println("RM_R02_LATE_FACT_STALE_ROUTE_ORACLE_PASS amount=250 orders=2");
    }
    private void assertStatisticalView(ReportSourceManifest manifest,UUID tenant,String previousAmount){
        var metric=AuthorizedAnalyticalReadService.Metric.BOOKED_AMOUNT;
        var all=ReportStatisticalView.select(manifest,metric,ReportStatisticalView.Range.M24);
        assertThat(all.tenant()).isEqualTo(tenant);
        assertThat(all.scopeDigest()).isEqualTo(manifest.scopeDigest());
        assertThat(all.sourceDigest()).isEqualTo(manifest.sha256());
        assertThat(all.accountingPolicy().currency()).isEqualTo("KZT");
        assertThat(all.hintKey()).isEqualTo("metric.BOOKED_AMOUNT.hint");
        assertThat(all.points()).hasSize(24);
        assertThat(all.points().get(22).value()).isEqualByComparingTo(previousAmount);
        assertThat(all.points().get(22).quality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(all.points().getFirst().value()).isNull();
        assertThat(all.points().getFirst().quality()).isEqualTo(DataQuality.MISSING);
        for(var range:ReportStatisticalView.Range.values()){
            var selected=ReportStatisticalView.select(manifest,metric,range);
            assertThat(selected.points()).containsExactlyElementsOf(all.points().subList(24-range.months,24));
            assertThat(selected.comparisons()).isEqualTo(all.comparisons());
            assertThat(selected.sourceDigest()).isEqualTo(all.sourceDigest());
        }
        assertThat(all.comparisons()).hasSize(2);
        var mom=all.comparisons().getFirst();
        assertThat(mom.basis()).isEqualTo(ReportStatisticalView.Basis.MOM);
        assertThat(mom.prior()).isEqualTo(all.points().get(22).period());
        assertThat(mom.result().change().quality()).isEqualTo(DataQuality.PARTIAL);
        assertThat(mom.result().change().value()).isNull();
        assertThat(mom.result().growth().value()).isNull();
        var yoy=all.comparisons().getLast();
        assertThat(yoy.basis()).isEqualTo(ReportStatisticalView.Basis.YOY);
        assertThat(yoy.prior()).isEqualTo(all.points().get(11).period());
        assertThat(yoy.result().growth().quality()).isEqualTo(DataQuality.MISSING);
        assertThat(yoy.result().growth().value()).isNull();
        var count=ReportStatisticalView.select(manifest,AuthorizedAnalyticalReadService.Metric.BOOKED_ORDERS,ReportStatisticalView.Range.M3);
        assertThat(count.points()).allSatisfy(p->{assertThat(p.unit()).isEqualTo("COUNT");assertThat(p.currency()).isNull();});
        System.out.println("RM_ARS_R12A_STATISTICAL_VIEW_QUALITY_ORACLE_PASS");
    }
    /** Real owner change, immutable old request and stale snapshot rejection. */
    public void accountingPolicyRevision(VerifiedActor owner,VerifiedActor staff,UUID tenant,long calendar,LocalDate last){
        var policies=context.getBean(kz.rm.organization.TenantCurrencyPolicyService.class);
        var old=policies.get(owner,tenant);
        var oldVersion=jdbc.queryForObject("SELECT policy_version FROM public.tenant_currency_policy WHERE tenant_id=?",Long.class,tenant);
        var previous=new AuthorizedAnalyticalReadService.AccountingPolicy(old.marketCountry(),old.accountingCurrency(),oldVersion);
        var before=materializer.materializeAccounting(staff,tenant,calendar,last,"empty-"+tenant);
        boolean enabled=policies.listAllowed(owner,tenant).stream().anyMatch(a->
            "USD".equals(a.currency())&&"PURCHASE".equals(a.purpose())&&"ENABLED".equals(a.state()));
        var updated=policies.setCurrencyEnabled(owner,tenant,"USD","PURCHASE",!enabled,old.rowVersion());
        assertThat(updated.rowVersion()).isEqualTo(old.rowVersion()+1);
        assertThat(updated.accountingCurrency()).isEqualTo(old.accountingCurrency());
        assertThatThrownBy(()->materializer.materializeAccounting(staff,tenant,calendar,last,"empty-"+tenant))
            .isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("INT_REPORT_REQUEST_CONFLICT");
        var qs=java.util.List.of(
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.BOOKED_AMOUNT,calendar,
                PeriodResolver.Code.M,last,null,null,null,java.util.List.of(),old.accountingCurrency()),
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.BOOKED_ORDERS,calendar,
                PeriodResolver.Code.M,last,null,null,null,java.util.List.of(),null));
        assertThatThrownBy(()->reader.readAccountingReportMonths(staff,tenant,qs,previous))
            .isInstanceOf(IllegalStateException.class).hasMessage("REPORT_ACCOUNTING_POLICY_CHANGED");
        var after=materializer.materializeAccounting(staff,tenant,calendar,last,"policy-"+tenant);
        assertReceipt(after,tenant,0);
        assertThat(after.dataset()).isNotEqualTo(before.dataset());
        assertThat(after.scopeDigest()).isEqualTo(before.scopeDigest());
        assertThat(after.sourceDigest()).isNotEqualTo(before.sourceDigest());
        assertThat(jdbc.queryForObject("""
            SELECT encode(sha256(source_bytes),'hex') FROM reporting.int_report_source_evidence
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
            """,String.class,tenant,before.dataset(),before.stagingDate())).isEqualTo(before.sourceDigest());
        // Fixture-only REVIEW_REQUIRED transition; this is not a new business policy API.
        assertThat(jdbc.update("UPDATE public.tenant_currency_policy SET status='REVIEW_REQUIRED' WHERE tenant_id=?",tenant)).isEqualTo(1);
        try{
            assertThatThrownBy(()->materializer.materializeAccounting(staff,tenant,calendar,last,"policy-"+tenant))
                .isInstanceOf(IllegalStateException.class).hasMessage("REPORT_ACCOUNTING_POLICY_NOT_ACTIVE");
        }finally{
            assertThat(jdbc.update("UPDATE public.tenant_currency_policy SET status='ACTIVE' WHERE tenant_id=?",tenant)).isEqualTo(1);
        }
        System.out.println("RM_R02_ACCOUNTING_POLICY_REVISION_ORACLE_PASS");
    }

    private static final class CountingJdbc extends JdbcTemplate {
        int l0;
        CountingJdbc(JdbcTemplate jdbc){super(java.util.Objects.requireNonNull(jdbc.getDataSource()));}
        @Override public <T> T queryForObject(String sql,Class<T> type,Object... args){
            if(sql.contains("FROM crm.int_l2_expected_source")&&sql.contains("stream_code=?"))l0++;
            return super.queryForObject(sql,type,args);
        }
        @Override public void query(String sql,org.springframework.jdbc.core.RowCallbackHandler handler,Object... args){
            if(sql.contains("JOIN crm.int_l2_expected_source")&&sql.contains("GROUP BY r.ordinal"))l0++;
            super.query(sql,handler,args);
        }
    }
    private static final class CountingL3 extends L3DerivedSeriesService{
        int calls;
        CountingL3(JdbcTemplate jdbc,PlatformTransactionManager manager,ApplicationContext context){
            super(jdbc,manager,context.getBean(MultidimensionalCoverageService.class),
                context.getBean(kz.rm.iam.TenantAuthorization.class),context.getBean(kz.rm.iam.WarehouseStaffScopeService.class));
        }
        @Override java.util.List<Observation> readPeriods(UUID tenant,UUID gen,Metric metric,Filter f,PeriodResolver.Code code,
                java.util.List<PeriodResolver.Period> periods,Snapshot snapshot){
            calls++;
            return super.readPeriods(tenant,gen,metric,f,code,periods,snapshot);
        }
    }
    /** Stable fixture: independent ordinary single-period reads versus report batch.
     * Ignore only snapshot observation time; compare exact numeric value and all route,
     * quality, period, currency, revision and proof fields. No live mutation during this test. */
    private void assertBatchParity(VerifiedActor actor,UUID tenant,long calendar,LocalDate last,String currency){
        var queries=java.util.List.of(
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.BOOKED_AMOUNT,
                calendar,PeriodResolver.Code.M,last,null,null,null,java.util.List.of(),currency),
            new AuthorizedAnalyticalReadService.Query(AuthorizedAnalyticalReadService.Metric.BOOKED_ORDERS,
                calendar,PeriodResolver.Code.M,last,null,null,null,java.util.List.of(),null));
        var counting=new CountingJdbc(jdbc);
        var l3=new CountingL3(counting,manager,context);
        var instrumented=new AuthorizedAnalyticalReadService(counting,manager,l3,context.getBean(MultidimensionalCoverageService.class));
        long batchStart=System.nanoTime();
        var manifest=instrumented.readDiagnosticMonths(actor,tenant,queries);
        assertThatThrownBy(()->ReportStatisticalView.select(manifest,queries.getFirst().metric(),ReportStatisticalView.Range.M24))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("REPORT_ACCOUNTING_MANIFEST_REQUIRED");
        long batchNanos=System.nanoTime()-batchStart;
        int batchL0=counting.l0,batchL3=l3.calls;
        assertThat(batchL0).isBetween(0,2);
        assertThat(batchL3).isBetween(0,2);
        assertThat(batchL0+batchL3).isBetween(2,4);
        counting.l0=0;l3.calls=0;long singleNanos=0;
        assertThat(manifest.windows()).hasSize(2);
        for(int i=0;i<queries.size();i++){
            var q=queries.get(i);
            var points=manifest.windows().get(i);
            assertThat(points).hasSize(24);
            for(int j=0;j<24;j++){
                var point=points.get(j);
                long singleStart=System.nanoTime();
                var single=instrumented.read(actor,tenant,new AuthorizedAnalyticalReadService.Query(q.metric(),
                    calendar,PeriodResolver.Code.M,last.minusMonths(23-j),null,null,null,java.util.List.of(),q.currency()));
                singleNanos+=System.nanoTime()-singleStart;
                assertThat(point).usingRecursiveComparison().ignoringFields("observedAt","value").isEqualTo(single);
                if(single.value()==null)assertThat(point.value()).isNull();
                else assertThat(point.value()).isEqualByComparingTo(single.value());
            }
        }
        assertThat(manifest.windows().stream().flatMap(java.util.List::stream)
            .map(AuthorizedAnalyticalReadService.Result::observedAt).distinct().count()).isEqualTo(1L);
        assertThat(counting.l0+l3.calls).isGreaterThanOrEqualTo(48);
        System.out.printf("RM_R02_BATCH_QUERY_BUDGET_PASS batch_l0=%d batch_l3=%d single_l0=%d single_l3=%d batch_ns=%d single_ns=%d%n",
            batchL0,batchL3,counting.l0,l3.calls,batchNanos,singleNanos);
        System.out.printf("RM_R02_BATCH_48_PARITY_PASS currency=%s%n",currency);
    }
    private static void await(java.util.concurrent.CountDownLatch latch){
        try{if(!latch.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("R02_START_TIMEOUT");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private void historicalOrder(UUID tenant,UUID template,LocalDate month,String currency,String... amounts){
        UUID id=UUID.randomUUID();
        var at=month.atStartOfDay().plusHours(12).atOffset(java.time.ZoneOffset.UTC);
        var total=java.util.Arrays.stream(amounts).map(java.math.BigDecimal::new)
            .reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
        assertThat(jdbc.update("""
            INSERT INTO crm.sales_order(tenant_id,id,pending_email_identity_id,buyer_user_id,status,total_amount,currency,created_at,fulfillment_mode)
            SELECT tenant_id,?,pending_email_identity_id,buyer_user_id,'PLACED',?,?,?,fulfillment_mode
            FROM crm.sales_order WHERE tenant_id=? AND id=?
            """,id,total,currency,at,tenant,template)).isEqualTo(1);
        for(String amount:amounts)
            assertThat(jdbc.update("""
                INSERT INTO crm.sales_order_line(tenant_id,id,order_id,offer_id,title_snapshot,quantity,unit_price_snapshot,currency,catalog_item_id_snapshot,price_source)
                SELECT tenant_id,?,?,offer_id,title_snapshot,1,?,?,catalog_item_id_snapshot,price_source
                FROM crm.sales_order_line WHERE tenant_id=? AND order_id=?
                """,UUID.randomUUID(),id,new java.math.BigDecimal(amount),currency,tenant,template)).isEqualTo(1);
        UUID event=UUID.randomUUID();
        assertThat(jdbc.update("""
            INSERT INTO crm.sales_order_status_event(tenant_id,id,order_id,event_type,occurred_at)
            VALUES (?,?,?,'PLACED',?)
            """,tenant,event,id,at)).isEqualTo(1);
        // Historical replay: preserve the original source, do not capture current dimensions.
        assertThat(context.getBean(CanonicalFactAppender.class).append(new CanonicalFactKey(tenant,
            CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,event)))
            .isEqualTo(CanonicalFactAppender.Outcome.INSERTED);
    }
    private void assertReceipt(SalesReportMaterializer.Receipt r,UUID tenant,long nonNull){
        assertThat(r.tenant()).isEqualTo(tenant);
        assertThat(r.expected()).isEqualTo(r.observed());
        assertThat(r.observed().rows()).isEqualTo(48);
        assertThat(jdbc.queryForObject("""
            SELECT build_state FROM reporting.int_report_dataset_attempt
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
            """,String.class,tenant,r.dataset(),r.stagingDate())).isEqualTo("READY");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_dataset
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
              AND metric_value IS NOT NULL AND quality_code='PARTIAL'
            """,Long.class,tenant,r.dataset(),r.stagingDate())).isEqualTo(nonNull);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reporting.int_report_dataset
            WHERE tenant_id=? AND dataset_id=? AND staging_date=?
              AND metric_value IS NULL AND quality_code='MISSING'
            """,Long.class,tenant,r.dataset(),r.stagingDate())).isEqualTo(48L-nonNull);
    }
    private void assertMonth(SalesReportMaterializer.Receipt r,LocalDate month,String currency,String amount,String orders){
        var lo=month.atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        var hi=month.plusMonths(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC);
        // Independent owner-table oracle, no analytical view and no shared digest implementation.
        var count=jdbc.queryForObject("""
            SELECT count(*)::numeric FROM crm.sales_order_status_event e
            WHERE e.tenant_id=? AND e.event_type='PLACED' AND e.occurred_at>=? AND e.occurred_at<?
              AND EXISTS(SELECT 1 FROM crm.sales_order_line l WHERE l.tenant_id=e.tenant_id AND l.order_id=e.order_id)
              AND 1=(SELECT count(*) FROM crm.sales_order_status_event x
                WHERE x.tenant_id=e.tenant_id AND x.order_id=e.order_id AND x.event_type='PLACED')
            """,java.math.BigDecimal.class,r.tenant(),lo,hi);
        var sum=jdbc.queryForObject("""
            SELECT sum(l.quantity*l.unit_price_snapshot) FROM crm.sales_order_line l
            WHERE l.tenant_id=? AND trim(l.currency)=?
              AND EXISTS(SELECT 1 FROM crm.sales_order_status_event e WHERE e.tenant_id=l.tenant_id
                AND e.order_id=l.order_id AND e.event_type='PLACED' AND e.occurred_at>=? AND e.occurred_at<?)
              AND 1=(SELECT count(*) FROM crm.sales_order_status_event x
                WHERE x.tenant_id=l.tenant_id AND x.order_id=l.order_id AND x.event_type='PLACED')
            """,java.math.BigDecimal.class,r.tenant(),currency,lo,hi);
        assertThat(count).isEqualByComparingTo(orders);
        if(amount==null)assertThat(sum).isNull();else assertThat(sum).isEqualByComparingTo(amount);
        for(String metric:java.util.List.of("BOOKED_AMOUNT","BOOKED_ORDERS")){
            boolean money=metric.equals("BOOKED_AMOUNT");
            var expected=money?sum:count;
            var value=jdbc.queryForObject("""
                SELECT metric_value FROM reporting.int_report_dataset
                WHERE tenant_id=? AND dataset_id=? AND staging_date=? AND metric_code=?
                  AND from_inclusive=? AND until_exclusive=? AND currency IS NOT DISTINCT FROM ?
                  AND quality_code=?
                """,java.math.BigDecimal.class,r.tenant(),r.dataset(),r.stagingDate(),metric,lo,hi,
                money?currency:null,expected==null?"MISSING":"PARTIAL");
            if(expected==null)assertThat(value).isNull();else assertThat(value).isEqualByComparingTo(expected);
        }
    }
}
