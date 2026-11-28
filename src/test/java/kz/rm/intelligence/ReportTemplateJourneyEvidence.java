package kz.rm.intelligence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.SpreadsheetVersion;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTAxDataSource;
import org.openxmlformats.schemas.drawingml.x2006.chart.CTNumDataSource;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static org.assertj.core.api.Assertions.*;

/** The exact approved workbook catalog and real R02 accounting fixture. */
final class ReportTemplateJourneyEvidence {
    private ReportTemplateJourneyEvidence(){}
    // Only the isolated CI fixture enables file export. This never runs on a
    // production tenant and does not expose a report delivery endpoint.
    private static final Path OUTPUT=Path.of("target","rm-d06c-fixture-xlsx");
    private static boolean export(){return "true".equals(System.getenv("RM_TEST_XLSX_EVIDENCE"));}
    static void record(String code,UUID tenant,byte[] bytes){
        if(!code.matches("RM-R(0[1-9]|1[0-2])"))throw new AssertionError("Invalid report code");
        assertThat(bytes.length).isBetween(1,2*1024*1024);
        try(var original=ReportTemplateRenderer.open(code);var actual=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
            assertThat(actual.getNumberOfSheets()).isEqualTo(original.getNumberOfSheets());
            assertThat(actual.getSheet("Metadata").getRow(4).getCell(1).getStringCellValue()).isEqualTo(tenant.toString());
            for(int i=0;i<actual.getNumberOfSheets();i++){
                var out=actual.getSheetAt(i);var in=original.getSheetAt(i);
                assertThat(out.getSheetName()).isEqualTo(in.getSheetName());
                assertThat(out.getTables()).hasSize(in.getTables().size());
                for(int j=0;j<out.getTables().size();j++)
                    assertThat(out.getTables().get(j).getArea().formatAsString())
                        .isEqualTo(in.getTables().get(j).getArea().formatAsString());
                var drawing=out.getDrawingPatriarch();var input=in.getDrawingPatriarch();
                if(input==null){assertThat(drawing).isNull();continue;}
                assertThat(drawing).isNotNull();
                assertThat(drawing.getCharts()).hasSize(input.getCharts().size());
                for(var chart:drawing.getCharts()){
                    var plot=chart.getCTChartSpace().getChart().getPlotArea();
                    for(var line:plot.getLineChartList())for(var series:line.getSerList())checkCache(actual,series.getCat(),series.getVal());
                    for(var bar:plot.getBarChartList())for(var series:bar.getSerList())checkCache(actual,series.getCat(),series.getVal());
                }
            }
            if(export()){
                Files.createDirectories(OUTPUT);
                Files.write(OUTPUT.resolve(code+".xlsx"),bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            }
        }catch(IOException e){throw new UncheckedIOException(e);}
    }
    private static void checkCache(XSSFWorkbook b,CTAxDataSource category,CTNumDataSource values){
        var sr=category.getStrRef();var nr=values.getNumRef();
        var labels=range(b,sr.getF());var cells=range(b,nr.getF());
        assertThat(labels).hasSameSizeAs(cells);
        var expectedLabels=new LinkedHashMap<Long,String>();var expectedValues=new LinkedHashMap<Long,Double>();
        for(int i=0;i<cells.size();i++){
            var l=labels.get(i);var c=cells.get(i);
            if(l!=null&&l.getCellType()==CellType.STRING)expectedLabels.put((long)i,l.getStringCellValue());
            if(c!=null&&c.getCellType()==CellType.NUMERIC)expectedValues.put((long)i,c.getNumericCellValue());
        }
        var actualLabels=new LinkedHashMap<Long,String>();var actualValues=new LinkedHashMap<Long,Double>();
        var sc=sr.getStrCache();var nc=nr.getNumCache();
        assertThat(sc.getPtCount().getVal()).isEqualTo((long)labels.size());
        assertThat(nc.getPtCount().getVal()).isEqualTo((long)cells.size());
        for(var p:sc.getPtList())assertThat(actualLabels.put(p.getIdx(),p.getV())).isNull();
        for(var p:nc.getPtList())assertThat(actualValues.put(p.getIdx(),Double.parseDouble(p.getV()))).isNull();
        assertThat(actualLabels).isEqualTo(expectedLabels);assertThat(actualValues).isEqualTo(expectedValues);
    }
    private static List<Cell> range(XSSFWorkbook book,String formula){
        var area=new AreaReference(formula,SpreadsheetVersion.EXCEL2007);
        var first=area.getFirstCell();var last=area.getLastCell();
        var sheet=book.getSheet(first.getSheetName());
        assertThat(sheet).isNotNull();assertThat(first.getCol()).isEqualTo(last.getCol());
        List<Cell> result=new ArrayList<>();
        for(int i=first.getRow();i<=last.getRow();i++)result.add(sheet.getRow(i).getCell(first.getCol()));
        return result;
    }
    static void verifyCatalog(UUID tenant){
        if(!export())return;
        try{
            Set<String> expected=new TreeSet<>();
            for(int i=1;i<=12;i++)expected.add("RM-R%02d.xlsx".formatted(i));
            Set<String> actual=new TreeSet<>();
            try(var files=Files.list(OUTPUT)){files.forEach(p->actual.add(p.getFileName().toString()));}
            assertThat(actual).isEqualTo(expected);
            var manifest=new StringBuilder();
            int charts=0,tables=0;
            for(var name:expected){
                byte[] bytes=Files.readAllBytes(OUTPUT.resolve(name));
                try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
                    assertThat(book.getSheet("Metadata").getRow(4).getCell(1).getStringCellValue()).isEqualTo(tenant.toString());
                    for(int i=0;i<book.getNumberOfSheets();i++){
                        var sheet=book.getSheetAt(i);tables+=sheet.getTables().size();
                        var drawing=sheet.getDrawingPatriarch();if(drawing!=null)charts+=drawing.getCharts().size();
                    }
                }
                var sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                manifest.append(sha).append("  ").append(name).append('\n');
            }
            assertThat(charts).isEqualTo(14);assertThat(tables).isEqualTo(20);
            Files.writeString(OUTPUT.resolve("SHA256SUMS"),manifest.toString(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            System.out.println("RM_D06C_ALL_12_XLSX_FIXTURE_ORACLE_PASS files=12 charts=14 tables=20 source_gated=3");
        }catch(IOException e){throw new UncheckedIOException(e);}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static void qualify(ReportSourceManifest manifest,String priorAmount){
        int charts=0,tables=0;
        for(int i=1;i<=12;i++){
            try(var b=ReportTemplateRenderer.open("RM-R%02d".formatted(i))){
                assertThat(b.getSheet("Dashboard")).isNotNull();
                assertThat(b.getSheet("Metadata")).isNotNull();
                for(int j=0;j<b.getNumberOfSheets();j++){
                    var sheet=b.getSheetAt(j);tables+=sheet.getTables().size();
                    var drawing=sheet.getDrawingPatriarch();
                    if(drawing!=null)charts+=drawing.getCharts().size();
                }
            }catch(IOException e){throw new UncheckedIOException(e);}
        }
        assertThat(charts).isEqualTo(14);assertThat(tables).isEqualTo(20);
        for(String code:java.util.List.of("RM-R01","RM-R02")){
            byte[] bytes=ReportTemplateRenderer.renderBooked(code,manifest);
            try(var b=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
                assertThat(b.getSheet("Metadata").getRow(4).getCell(1).getStringCellValue()).isEqualTo(manifest.tenant().toString());
                var history=b.getSheet("History");
                assertThat(history.getRow(23).getCell(1).getNumericCellValue())
                    .isEqualTo(new java.math.BigDecimal(priorAmount).movePointLeft(6).doubleValue());
                assertThat(history.getRow(23).getCell(2).getCellType()).isEqualTo(CellType.BLANK);
                assertThat(history.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.BLANK);
                assertThat(history.getRow(23).getCell(3).getStringCellValue()).contains("PARTIAL","FULFILLED=MISSING");
                assertThat(b.getSheet("Booked").getRow(12).getCell(4).getCellType()).isEqualTo(CellType.BLANK);
                assertThat(b.getSheet("Dashboard").getRow(9).getCell(0).getCellType()).isEqualTo(CellType.BLANK);
                var cs=b.getSheet("Dashboard").getDrawingPatriarch().getCharts();
                assertThat(cs).hasSize(2);
                for(var chart:cs)for(var line:chart.getCTChartSpace().getChart().getPlotArea().getLineChartList())
                    for(var ser:line.getSerList()){
                        var ref=ser.getVal().getNumRef();var cache=ref.getNumCache();
                        if(ref.getF().contains("Dynamics"))assertThat(cache.sizeOfPtArray()).isZero();
                        else{
                            int col=ref.getF().contains("$B$")?1:2;
                            var sheet=b.getSheet("Booked");
                            long expected=java.util.stream.IntStream.rangeClosed(1,12).filter(r->
                                sheet.getRow(r).getCell(col).getCellType()==CellType.NUMERIC).count();
                            assertThat(cache.sizeOfPtArray()).isEqualTo((int)expected);
                            for(var pt:cache.getPtList())
                                assertThat(Double.parseDouble(pt.getV())).isEqualTo(sheet.getRow((int)pt.getIdx()+1).getCell(col).getNumericCellValue());
                        }
                    }
                for(var sheet:b)for(var row:sheet)for(var cell:row){
                    assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
                    if(cell.getCellType()==CellType.STRING)
                        assertThat(cell.getStringCellValue()).doesNotContain("TENANT-A","SYNTHETIC MOCKUP","SYNTHETIC_NOT_QUALIFIED","DESIGN MOCK");
                }
            }catch(IOException e){throw new UncheckedIOException(e);}
            try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){
                for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()){
                    if(entry.getName().endsWith(".xml")){
                        var xml=new String(zip.readNBytes(4*1024*1024+1),StandardCharsets.UTF_8);
                        assertThat(xml).doesNotContain("TENANT-A","SYNTHETIC MOCKUP","SYNTHETIC_NOT_QUALIFIED","DESIGN MOCK","-DEMO");
                    }
                }
            }catch(IOException e){throw new UncheckedIOException(e);}
            record(code,manifest.tenant(),bytes);
        }
        System.out.println("RM_D06C_BOOKED_TEMPLATE_ORACLE_PASS catalog=12 charts=14 tables=20 populated=2");
    }
}
