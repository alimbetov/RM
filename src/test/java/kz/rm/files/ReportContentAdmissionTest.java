package kz.rm.files;

import kz.rm.security.kms.FileContentCryptoPort.Format;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportContentAdmissionTest {
    @TempDir Path root;
    private static final String CT="http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String MAIN="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String TYPES="<Types xmlns='"+CT+"'><Override PartName='/xl/workbook.xml' ContentType='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml'/></Types>";
    private static final String ROOT="<Relationships xmlns='"+REL+"'><Relationship Id='r1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='xl/workbook.xml'/></Relationships>";
    private static final String WORKBOOK="<workbook xmlns='"+MAIN+"'><sheets><sheet name='A' sheetId='1'/></sheets></workbook>";
    private static final String WORKBOOK_RELS="<Relationships xmlns='"+REL+"'><Relationship Id='r1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet' Target='worksheets/sheet1.xml'/></Relationships>";
    private static Map<String,String> entries(){
        return new LinkedHashMap<>(Map.of("[Content_Types].xml",TYPES,"_rels/.rels",ROOT,
            "xl/workbook.xml",WORKBOOK,"xl/_rels/workbook.xml.rels",WORKBOOK_RELS,
            "xl/worksheets/sheet1.xml","<worksheet xmlns='"+MAIN+"'/>"));
    }
    private static byte[] zip(Map<String,String> items)throws IOException{
        var out=new ByteArrayOutputStream();
        try(var z=new ZipOutputStream(out)){
            for(var e:items.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue().getBytes(StandardCharsets.UTF_8));z.closeEntry();}
        }
        return out.toByteArray();
    }
    @Test void allTwelveActualTemplatePackagesAndMinimalFixture() throws Exception{
        ReportContentInspection.require(Format.XLSX,zip(entries()));
        for(int i=1;i<=12;i++)try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/report-templates/RM-R%02d.xlsx".formatted(i)))){
            ReportContentInspection.require(Format.XLSX,in.readAllBytes());
        }
    }
    @Test void effectiveWorkbookContentTypeHonorsDefaultAndOverridePrecedence()throws Exception{
        var fallback=entries();
        fallback.put("[Content_Types].xml",TYPES.replace("Override PartName='/xl/workbook.xml'","Default Extension='xml'"));
        ReportContentInspection.require(Format.XLSX,zip(fallback));
        var wrong=new LinkedHashMap<>(fallback);
        wrong.put("[Content_Types].xml",fallback.get("[Content_Types].xml").replace("</Types>",
            "<Override PartName='/xl/workbook.xml' ContentType='application/xml'/></Types>"));
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.XLSX,zip(wrong)));
        var duplicate=entries();
        duplicate.put("[Content_Types].xml",TYPES.replace("</Types>",
            "<Override PartName='/xl/workbook.xml' ContentType='application/xml'/></Types>"));
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.XLSX,zip(duplicate)));
    }
    @Test void rejectsWrongContainerTraversalMacroExternalXmlAndInflation() throws Exception{
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.XLSX,new byte[]{80,75,3,4}));
        for(var extra:List.of("../escape","xl/vbaProject.bin","xl/externalLinks/externalLink1.xml")){
            var map=entries();map.put(extra,"X");assertThrows(IOException.class,()->ReportContentInspection.require(Format.XLSX,zip(map)));
        }
        var noWorkbook=entries();noWorkbook.remove("xl/workbook.xml");
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.XLSX,zip(noWorkbook)));
        var external=entries();external.put("xl/_rels/workbook.xml.rels",WORKBOOK_RELS.replace("Target='worksheets","TargetMode='External' Target='worksheets"));
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.XLSX,zip(external)));
        var dtd=entries();dtd.put("xl/workbook.xml","<!DOCTYPE workbook [<!ENTITY x SYSTEM 'file:///untrusted'>]>"+WORKBOOK);
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.XLSX,zip(dtd)));
        var bomb=entries();bomb.put("xl/worksheets/sheet1.xml","<worksheet xmlns='"+MAIN+"'>"+"x".repeat(2*1024*1024)+"</worksheet>");
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.XLSX,zip(bomb)));
        byte[] valid=zip(entries());
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.XLSX,Arrays.copyOf(valid,valid.length-1)));
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.XLSX,Arrays.copyOf(valid,valid.length+1)));
    }
    @Test void pdfStructuralPreflightNotJustMagic() throws Exception{
        byte[] pdf="%PDF-1.4\nxref\n0 1\n0000000000 65535 f \ntrailer\n<< /Size 1 >>\nstartxref\n9\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        ReportContentInspection.require(Format.PDF,pdf);
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.PDF,"%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IOException.class,()->ReportContentInspection.require(Format.PDF,zip(entries())));
    }
    @Test void authenticatedClamAvReceivesExactBytesAndRejectsTruncatedReply()throws Exception{
        byte[] payload=zip(entries());
        for(boolean terminated:new boolean[]{true,false}){
            try(var server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
                var pool=Executors.newSingleThreadExecutor()){
                var peer=pool.submit(()->{
                    try(var c=server.accept();var in=new DataInputStream(c.getInputStream())){
                        assertEquals("zINSTREAM\0",new String(in.readNBytes(10),StandardCharsets.US_ASCII));
                        var captured=new ByteArrayOutputStream();
                        for(int n=in.readInt();n!=0;n=in.readInt()){assertTrue(n>0&&n<=32768);captured.write(in.readNBytes(n));}
                        assertArrayEquals(payload,captured.toByteArray());
                        c.getOutputStream().write((terminated?"stream: OK\0":"stream: OK").getBytes(StandardCharsets.US_ASCII));
                        c.getOutputStream().flush();
                    }catch(IOException e){throw new UncheckedIOException(e);}
                });
                var scanner=new ClamAvFileScanner(new LocalFileStorage(root.toString()),server.getInetAddress().getHostAddress(),server.getLocalPort(),26214400);
                assertEquals(terminated?FileScanPort.Verdict.CLEAN:FileScanPort.Verdict.ERROR,
                    scanner.scanAuthenticated(new ByteArrayInputStream(payload),payload.length));
                peer.get(5,TimeUnit.SECONDS);
            }
        }
    }
}
