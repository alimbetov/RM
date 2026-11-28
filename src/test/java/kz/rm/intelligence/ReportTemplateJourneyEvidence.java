package kz.rm.intelligence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import static org.assertj.core.api.Assertions.*;

/** The exact approved workbook catalog and real R02 accounting fixture. */
final class ReportTemplateJourneyEvidence {
    private ReportTemplateJourneyEvidence(){}
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
        }
        System.out.println("RM_D06C_BOOKED_TEMPLATE_ORACLE_PASS catalog=12 charts=14 tables=20 populated=2");
    }
}
