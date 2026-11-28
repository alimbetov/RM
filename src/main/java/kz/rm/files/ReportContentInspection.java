package kz.rm.files;

import kz.rm.security.kms.FileContentCryptoPort.Format;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.xml.stream.*;

/** Strict, bounded in-memory report admission, not a general document sanitizer.
 * No filesystem extraction, DTD, external XML access or unbounded inflation. */
final class ReportContentInspection {
    private static final int MAX_ENTRIES=512, MAX_INFLATED=32*1024*1024, MAX_ENTRY=8*1024*1024;
    private static final String CT="http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String MAIN="http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String WORKBOOK_TYPE="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
    private ReportContentInspection(){}

    static void require(Format format,byte[] bytes) throws IOException {
        if(bytes==null||bytes.length<1||bytes.length>format.maxBytes())throw invalid();
        if(format==Format.PDF){pdf(bytes);return;}
        xlsx(bytes);
    }
    private static IOException invalid(){return new IOException("REPORT_FORMAT_REJECTED");}
    private static void pdf(byte[] b) throws IOException {
        // Structural preflight only; no PDF renderer or PDF sanitization admitted.
        String start=new String(b,0,Math.min(b.length,16),StandardCharsets.US_ASCII);
        int n=b.length;
        while(n>0&&(b[n-1]==10||b[n-1]==13||b[n-1]==32||b[n-1]==9))n--;
        if(!start.matches("(?s)%PDF-(1\\.[0-7]|2\\.0)[\\r\\n].*")||n<5||
            !new String(b,n-5,5,StandardCharsets.US_ASCII).equals("%%EOF"))throw invalid();
        String tail=new String(b,Math.max(0,n-2048),Math.min(n,2048),StandardCharsets.US_ASCII);
        var m=java.util.regex.Pattern.compile("startxref\\s+(\\d{1,10})\\s+%%EOF$").matcher(tail);
        if(!m.find())throw invalid();
        long offset=Long.parseLong(m.group(1));
        if(offset<9||offset>=n-5)throw invalid();
        // Classic xref table or xref stream object header.
        String target=new String(b,(int)offset,Math.min(64,n-(int)offset),StandardCharsets.US_ASCII);
        if(!target.startsWith("xref")&&!target.matches("(?s)\\d+\\s+\\d+\\s+obj\\b.*"))throw invalid();
    }
    private static void xlsx(byte[] bytes) throws IOException {
        Set<String> central=centralNames(bytes);
        Set<String> names=new HashSet<>();
        Set<String> worksheets=new HashSet<>();
        Set<String> workbookTargets=new HashSet<>();
        boolean types=false,rootRel=false,workbook=false,workbookRel=false;
        long total=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8)){
            java.util.zip.ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                String name=entry.getName();
                if(entry.isDirectory()||!allowedName(name)||!names.add(name)||names.size()>MAX_ENTRIES)throw invalid();
                long count=0;
                boolean xml=name.endsWith(".xml")||name.endsWith(".rels");
                var data=xml?new ByteArrayOutputStream():null;
                byte[] buf=new byte[8192];int n;
                while((n=zip.read(buf))!=-1){
                    if(n==0)continue;
                    count+=n;total+=n;
                    if(count>MAX_ENTRY||total>MAX_INFLATED||total>Math.max(1024*1024L,100L*bytes.length))throw invalid();
                    if(data!=null)data.write(buf,0,n);
                }
                zip.closeEntry();
                if(entry.getCompressedSize()>0&&count>Math.max(64*1024L,100L*entry.getCompressedSize()))throw invalid();
                if(data!=null){
                    XmlInfo info=xml(name,data.toByteArray());
                    if(name.equals("[Content_Types].xml"))types=info.workbookType;
                    if(name.equals("_rels/.rels"))rootRel=info.workbookRelationship;
                    if(name.equals("xl/workbook.xml"))workbook=info.sheet;
                    if(name.equals("xl/_rels/workbook.xml.rels")){workbookRel=true;workbookTargets.addAll(info.worksheetTargets);}
                    if(name.startsWith("xl/worksheets/")&&name.endsWith(".xml")&&!name.contains("/_rels/")){
                        if(!info.worksheet)throw invalid();
                        worksheets.add(name);
                    }
                }
            }
        }catch(XMLStreamException|IllegalArgumentException ex){throw invalid();}
        if(!names.equals(central)||!types||!rootRel||!workbook||!workbookRel||workbookTargets.isEmpty()
            ||!worksheets.containsAll(workbookTargets))throw invalid();
    }
    private static boolean allowedName(String n){
        String lower=n.toLowerCase(Locale.ROOT);
        if(n.length()>240||n.startsWith("/")||n.contains("\\")||n.contains(":")||n.contains("%")
            ||lower.contains("vbaproject")||lower.contains("macrosheet")||lower.contains("externallink")
            ||lower.contains("activex")||lower.contains("/embeddings/")||lower.contains("customui")
            ||lower.contains("oleobject")||lower.contains("vbadata"))return false;
        return Arrays.stream(n.split("/",-1)).noneMatch(s->s.isEmpty()||s.equals(".")||s.equals("..")
            ||s.chars().anyMatch(c->c<32||c==127));
    }
    /** Require ordinary single-disk ZIP with canonical end record and bounded central directory. */
    private static Set<String> centralNames(byte[] b) throws IOException {
        if(b.length<22)throw invalid();
        ByteBuffer v=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int e=b.length-22;
        if(v.getInt(e)!=0x06054b50||v.getShort(e+4)!=0||v.getShort(e+6)!=0||v.getShort(e+20)!=0)throw invalid();
        int count=Short.toUnsignedInt(v.getShort(e+10));
        int size=v.getInt(e+12),off=v.getInt(e+16);
        if(count<1||count>MAX_ENTRIES||Short.toUnsignedInt(v.getShort(e+8))!=count
            ||size<0||off<0||(long)size+off!=e)throw invalid();
        Set<String> names=new HashSet<>();Set<Integer> offsets=new HashSet<>();int p=off;
        for(int i=0;i<count;i++){
            if(p>e-46||v.getInt(p)!=0x02014b50)throw invalid();
            int flags=Short.toUnsignedInt(v.getShort(p+8)),method=Short.toUnsignedInt(v.getShort(p+10));
            int len=Short.toUnsignedInt(v.getShort(p+28)),extra=Short.toUnsignedInt(v.getShort(p+30)),
                comment=Short.toUnsignedInt(v.getShort(p+32)),local=v.getInt(p+42);
            if((flags&1)!=0||(method!=0&&method!=8)||len<1||len>240||v.getShort(p+34)!=0
                ||(long)p+46+len+extra+comment>e||local<0||local>off-30||!offsets.add(local)
                ||v.getInt(local)!=0x04034b50)throw invalid();
            int localLen=Short.toUnsignedInt(v.getShort(local+26));
            if(localLen!=len||(long)local+30+len>off)throw invalid();
            byte[] name=Arrays.copyOfRange(b,p+46,p+46+len);
            if(!Arrays.equals(name,Arrays.copyOfRange(b,local+30,local+30+len)))throw invalid();
            String n=new String(name,StandardCharsets.UTF_8);
            if(!allowedName(n)||!names.add(n))throw invalid();
            p+=46+len+extra+comment;
        }
        if(p!=e||!offsets.contains(0))throw invalid();
        return names;
    }
    private static final class XmlInfo {
        boolean workbookType,workbookRelationship,sheet,worksheet;
        Set<String> worksheetTargets=new HashSet<>();
    }
    private static XmlInfo xml(String name,byte[] bytes) throws XMLStreamException,IOException {
        var factory=XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        factory.setXMLResolver((publicId,systemId,base,ns)->{throw new XMLStreamException("external resolution denied");});
        var r=factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
        XmlInfo info=new XmlInfo();int depth=0,nodes=0;
        try {
            while(r.hasNext()){
                int event=r.next();
                if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE)throw invalid();
                if(event==XMLStreamConstants.START_ELEMENT){
                    if(++depth>64||++nodes>200000||r.getAttributeCount()>128)throw invalid();
                    String tag=r.getLocalName(),ns=r.getNamespaceURI();
                    if(depth==1){
                        if(name.equals("[Content_Types].xml")&&(!"Types".equals(tag)||!CT.equals(ns)))throw invalid();
                        if(name.endsWith(".rels")&&(!"Relationships".equals(tag)||!REL.equals(ns)))throw invalid();
                        if(name.equals("xl/workbook.xml")&&(!"workbook".equals(tag)||!MAIN.equals(ns)))throw invalid();
                        info.worksheet="worksheet".equals(tag)&&MAIN.equals(ns);
                    }
                    if(CT.equals(ns)&&("Override".equals(tag)||"Default".equals(tag))){
                        String type=r.getAttributeValue(null,"ContentType");
                        if(type==null)throw invalid();
                        String lower=type.toLowerCase(Locale.ROOT);
                        if(lower.contains("macroenabled")||lower.contains("vba")||lower.contains("oleobject")||lower.contains("activex"))throw invalid();
                        if("/xl/workbook.xml".equals(r.getAttributeValue(null,"PartName"))&&WORKBOOK_TYPE.equals(type))info.workbookType=true;
                    }
                    if(REL.equals(ns)&&"Relationship".equals(tag)){
                        String mode=r.getAttributeValue(null,"TargetMode"),target=r.getAttributeValue(null,"Target"),
                            type=r.getAttributeValue(null,"Type");
                        if((mode!=null&&!"Internal".equals(mode))||target==null||type==null||target.contains(":")
                            ||target.contains("\\")||target.contains("%")||target.startsWith("//")
                            ||type.toLowerCase(Locale.ROOT).contains("externallink"))throw invalid();
                        if(name.equals("_rels/.rels")&&type.endsWith("/officeDocument")
                            &&(target.equals("xl/workbook.xml")||target.equals("/xl/workbook.xml")))info.workbookRelationship=true;
                        if(name.equals("xl/_rels/workbook.xml.rels")&&type.endsWith("/worksheet")){
                            String path=target.startsWith("/")?target.substring(1):"xl/"+target;
                            if(!allowedName(path)||!path.startsWith("xl/worksheets/"))throw invalid();
                            info.worksheetTargets.add(path);
                        }
                    }
                    if(name.equals("xl/workbook.xml")&&MAIN.equals(ns)&&"sheet".equals(tag))info.sheet=true;
                }else if(event==XMLStreamConstants.END_ELEMENT)depth--;
            }
            return info;
        }finally{r.close();}
    }
}
