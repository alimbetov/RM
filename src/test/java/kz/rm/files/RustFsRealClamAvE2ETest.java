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

            byte[] infected=pdf(EICAR);
            var dangerous=store.stage(tenant,UUID.randomUUID(),
                    new ByteArrayInputStream(infected),infected.length,"application/pdf");
            try {
                assertThat(scanner.scan(dangerous.key())).isEqualTo(FileScanPort.Verdict.INFECTED);
                assertThat(new ClamAvFileScanner(store,"127.0.0.1",1,26_214_400)
                    .scan(dangerous.key())).isEqualTo(FileScanPort.Verdict.ERROR);
            } finally {store.delete(dangerous.key());}
        }
    }
}
