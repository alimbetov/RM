package kz.rm.intelligence;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;

/** Canonical ZIP packaging for trusted R01/R02 renderers, not arbitrary uploads.
 * The sealed observation time is the report's stable logical generation time.
 * No disk or ZIP extra fields. The byte-equality gate still fails closed on drift. */
final class StableReportPackage {
    private StableReportPackage(){}
    static byte[] canonical(byte[] input,Instant observed)throws IOException{
        if(input.length>2*1024*1024)throw new IOException("REPORT_OUTPUT_SIZE");
        var entries=new TreeMap<String,byte[]>();long total=0;
        try(var z=new ZipInputStream(new ByteArrayInputStream(input))){
            for(var e=z.getNextEntry();e!=null;e=z.getNextEntry()){
                if(entries.size()>=512||e.isDirectory())throw new IOException("REPORT_PACKAGE_ENTRY");
                byte[] b=z.readNBytes(8*1024*1024+1);
                if(b.length>8*1024*1024||(total+=b.length)>32*1024*1024)throw new IOException("REPORT_PACKAGE_BUDGET");
                if(e.getName().equals("docProps/core.xml")){
                    // POI may stamp core creation/modification dates on write.
                    // Only replace timestamp element text; template XML stays intact.
                    String s=new String(b,StandardCharsets.UTF_8);
                    s=s.replaceAll("(<(?:[A-Za-z0-9_]+:)?(?:created|modified)(?:\\s[^>]*)?>)[^<]*(</(?:[A-Za-z0-9_]+:)?(?:created|modified)>)",
                        "$1"+observed.toString()+"$2");
                    b=s.getBytes(StandardCharsets.UTF_8);
                }
                if(entries.put(e.getName(),b)!=null)throw new IOException("REPORT_PACKAGE_DUPLICATE");
            }
        }
        var out=new ByteArrayOutputStream();
        try(var z=new ZipOutputStream(out,StandardCharsets.UTF_8)){
            z.setLevel(Deflater.DEFAULT_COMPRESSION);
            for(var e:entries.entrySet()){
                var part=new ZipEntry(e.getKey());
                part.setTimeLocal(LocalDateTime.of(2000,1,1,0,0));
                z.putNextEntry(part);z.write(e.getValue());z.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
