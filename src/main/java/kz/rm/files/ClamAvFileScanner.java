package kz.rm.files;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * ClamAV clamd INSTREAM adapter. Default deployment remains deny-all;
 * this bean is enabled only through explicit qualified operator configuration.
 */
@Component
@Primary
@ConditionalOnProperty(name="rm.files.clamav.enabled",havingValue="true")
class ClamAvFileScanner implements FileScanPort {
    private final FileStoragePort storage;
    private final String host;
    private final int port;
    private final int maxBytes;

    ClamAvFileScanner(FileStoragePort storage,
                      @Value("${rm.files.clamav.host:127.0.0.1}") String host,
                      @Value("${rm.files.clamav.port:3310}") int port,
                      @Value("${rm.files.clamav.max-bytes:26214400}") int maxBytes) {
        if(port<1 || port>65535 || maxBytes<1 || maxBytes>26_214_400)
            throw new IllegalArgumentException("ClamAV configuration outside FILES limits");
        this.storage=storage;this.host=host;this.port=port;this.maxBytes=maxBytes;
    }
    @Override public Verdict scan(String stagingKey) {
        try(var in=storage.openStagedForScan(stagingKey);
            var socket=new Socket()) {
            socket.connect(new InetSocketAddress(host,port),2000);
            socket.setSoTimeout(30_000);
            var out=new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] bytes=new byte[32*1024];
            long seen=0;
            int n;
            while((n=in.read(bytes))!=-1) {
                if(n==0)continue;
                seen+=n;
                if(seen>maxBytes)return Verdict.ERROR;
                out.writeInt(n);
                out.write(bytes,0,n);
            }
            out.writeInt(0);
            out.flush();
            // z-protocol ends replies with NUL; one bounded line from clamd.
            var reply=new ByteArrayOutputStream();
            int value;
            while((value=socket.getInputStream().read())>=0 && value!=0 && value!=10) {
                if(reply.size()>=1024)return Verdict.ERROR;
                reply.write(value);
            }
            String response=reply.toString(StandardCharsets.US_ASCII);
            if("stream: OK".equals(response))return Verdict.CLEAN;
            if(response.startsWith("stream: ") && response.endsWith(" FOUND"))
                return Verdict.INFECTED;
            return Verdict.ERROR;
        } catch(IOException | RuntimeException ignored) {
            // Do not leak paths, raw clamd responses, or upload content.
            return Verdict.ERROR;
        }
    }
}
