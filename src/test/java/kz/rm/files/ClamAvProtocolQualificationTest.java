package kz.rm.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class ClamAvProtocolQualificationTest {
    @TempDir Path root;
    private FileStoragePort.Staged stage(LocalFileStorage storage,UUID tenant,UUID file)
            throws Exception {
        byte[] sample="%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        return storage.stage(tenant,file,new ByteArrayInputStream(sample),
                sample.length,"application/pdf");
    }
    @Test void clamdInstreamResponsesAreAdmittedOnlyForExactClean() throws Exception {
        var storage=new LocalFileStorage(root.toString());
        UUID tenant=UUID.randomUUID(),file=UUID.randomUUID();
        var staged=stage(storage,tenant,file);
        for(var expected:java.util.Map.of(
                "stream: OK",FileScanPort.Verdict.CLEAN,
                "stream: Eicar-Test-Signature FOUND",FileScanPort.Verdict.INFECTED,
                "stream: limit exceeded ERROR",FileScanPort.Verdict.ERROR).entrySet()) {
            try(var server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));
                var pool=Executors.newSingleThreadExecutor()) {
                var task=pool.submit(()->{
                    try(var connection=server.accept()) {
                        var in=new DataInputStream(connection.getInputStream());
                        byte[] command=new byte[10];in.readFully(command);
                        assertThat(new String(command,StandardCharsets.US_ASCII))
                                .isEqualTo("zINSTREAM\0");
                        while(true){
                            int len=in.readInt();
                            if(len==0)break;
                            assertThat(len).isBetween(1,32*1024);
                            in.skipNBytes(len);
                        }
                        connection.getOutputStream().write(
                                (expected.getKey()+"\0").getBytes(StandardCharsets.US_ASCII));
                        connection.getOutputStream().flush();
                    } catch(Exception e) {throw new RuntimeException(e);}
                });
                var scanner=new ClamAvFileScanner(storage,"127.0.0.1",
                        server.getLocalPort(),26_214_400);
                assertThat(scanner.scan(staged.key())).isEqualTo(expected.getValue());
                task.get(5,TimeUnit.SECONDS);
            }
        }
    }
    @Test void unavailableScannerAndInvalidPathAlwaysDeny() throws Exception {
        var storage=new LocalFileStorage(root.toString());
        var scanner=new ClamAvFileScanner(storage,"127.0.0.1",3310,26_214_400);
        assertThat(scanner.scan("../../secrets")).isEqualTo(FileScanPort.Verdict.ERROR);
    }
}
