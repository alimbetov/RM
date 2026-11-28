package kz.rm.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.BeforeAll;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Actual daemon scan of bytes stored in actual RustFS, not a mock S3 or mock AV.
 * This test does not substitute for the separate VerifiedActor + PG E2E.
 */
@EnabledIfEnvironmentVariable(named="RM_CLAMAV_LIVE_E2E",matches="true")
class RustFsRealClamAvE2ETest {
    private static final String EICAR="X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";

    private static S3Client client() {
        return S3Client.builder()
            .endpointOverride(URI.create(System.getenv("RM_RUSTFS_E2E_ENDPOINT")))
            .region(Region.of("us-east-1")).forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                System.getenv("RM_RUSTFS_E2E_ACCESS"),
                System.getenv("RM_RUSTFS_E2E_SECRET")))).build();
    }

    private static byte[] pdf(String payload) {
        return ("%PDF-1.4\n"+payload+"\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    @Test void realClamdRespondsCleanInfectedAndFailClosedForUnavailableEndpoint()
            throws Exception {
        try(var api=client()) {
            String bucket=System.getenv("RM_RUSTFS_E2E_BUCKET");
            try { api.createBucket(CreateBucketRequest.builder().bucket(bucket).build()); }
            catch(software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException ignored){}
            var store=new RustFsFileStorage(api,bucket);
            var scanner=new ClamAvFileScanner(store,"127.0.0.1",3310,26_214_400);
            UUID tenant=UUID.randomUUID(),file=UUID.randomUUID();
            byte[] safe=pdf("Synthetic ordinary financial attachment");
            var staged=store.stage(tenant,file,new ByteArrayInputStream(safe),
                    safe.length,"application/pdf");
            try {
                assertThat(scanner.scan(staged.key())).isEqualTo(FileScanPort.Verdict.CLEAN);
            } finally {store.delete(staged.key());}

            // EICAR must be the exact standalone standard bytes: wrapping the
            // signature in a PDF is NOT a reliable AV qualification fixture.
            byte[] eicar=EICAR.getBytes(StandardCharsets.US_ASCII);
            assertThat(eicar.length).isEqualTo(68);
            String infectedKey="rustfs/"+tenant+"/"+UUID.randomUUID()+"/stage-"+UUID.randomUUID();
            api.putObject(software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
                    .bucket(bucket).key(infectedKey.substring(7)).build(),
                software.amazon.awssdk.core.sync.RequestBody.fromBytes(eicar));
            try {
                assertThat(scanner.scan(infectedKey)).isEqualTo(FileScanPort.Verdict.INFECTED);
                assertThat(new ClamAvFileScanner(store,"127.0.0.1",1,26_214_400)
                    .scan(infectedKey)).isEqualTo(FileScanPort.Verdict.ERROR);
            } finally {store.delete(infectedKey);}
        }
    }
    @Test void actualClamAvScansAuthenticatedV1PlaintextNotCiphertext()throws Exception{
        try(var api=client()){
            String bucket=System.getenv("RM_RUSTFS_E2E_BUCKET");
            var store=new RustFsFileStorage(api,bucket);
            var scanner=new ClamAvFileScanner(store,"127.0.0.1",3310,26_214_400);
            var unavailable=new ClamAvFileScanner(store,"127.0.0.1",1,26_214_400);
            UUID tenant=UUID.randomUUID();
            var encrypted=new ReportEncryptedStorage(store,V1DaemonCryptoFixture.crypto(tenant));
            for(boolean infected:new boolean[]{false,true}){
                byte[] bytes=infected?EICAR.getBytes(StandardCharsets.US_ASCII):pdf("Synthetic ordinary report");
                var e=V1DaemonCryptoFixture.expected(tenant,UUID.randomUUID(),UUID.randomUUID(),bytes);
                // AV transport qualification only; EICAR is not admitted as PDF.
                String stage=encrypted.stage(e,new ByteArrayInputStream(bytes));
                try{
                    FileScanPort.Verdict verdict=encrypted.read(e,stage,true,in->scanner.scanAuthenticated(in,bytes.length));
                    assertThat(verdict).isEqualTo(infected?FileScanPort.Verdict.INFECTED:FileScanPort.Verdict.CLEAN);
                    FileScanPort.Verdict outage=encrypted.read(e,stage,true,in->unavailable.scanAuthenticated(in,bytes.length));
                    assertThat(outage).isEqualTo(FileScanPort.Verdict.ERROR);
                    assertThat(scanner.scan(stage)).isEqualTo(FileScanPort.Verdict.ERROR);
                }finally{store.delete(stage);}
            }
        }
    }

}
