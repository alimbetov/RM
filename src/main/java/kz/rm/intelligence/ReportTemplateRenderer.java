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
            meta(book,"As of",manifest.observedAt().toString());meta(book,"Generated at",Instant.now().toString());
            meta(book,"Mode","INTERNAL_DIAGNOSTIC");meta(book,"Visibility","NOT_PUBLISHED");
            meta(book,"Period bounds",values.getFirst().period().startInclusive()+" / "+values.getLast().period().endExclusive());
            meta(book,"Baseline bounds",values.getFirst().period().startInclusive()+" / "+values.get(11).period().endExclusive());
            meta(book,"Time zone",values.getLast().period().zoneId());meta(book,"Source revision",Long.toString(manifest.sourceRevision()));
            meta(book,"Source manifest SHA-256",manifest.sha256());meta(book,"Scope SHA-256",manifest.scopeDigest());
            meta(book,"Calendar version",Long.toString(values.getLast().period().calendarVersion()));
            meta(book,"Coverage","HISTORICAL_CONTINUITY_UNQUALIFIED");meta(book,"Limits","No fulfilled or unsupported metrics; no historical READY.");
            refreshCharts(book);
            book.write(out);byte[] bytes=out.toByteArray();
            if(bytes.length>2*1024*1024)throw new IllegalStateException("REPORT_OUTPUT_SIZE");
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
