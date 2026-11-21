package kz.rm.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Assumptions;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;

import static org.assertj.core.api.Assertions.*;

/**
 * Two Maven processes separated by a real Docker restart.
 * The checkpoint is on the CI runner, not inside the RustFS container.
 */
@EnabledIfEnvironmentVariable(named="RM_RUSTFS_LIVE_E2E",matches="true")
class RustFsRestartProbeTest {
    private static final Path CHECKPOINT=Path.of("target/rustfs-live-restart.properties");

    private static S3Client client() {
        return S3Client.builder()
                .endpointOverride(URI.create(System.getenv("RM_RUSTFS_E2E_ENDPOINT")))
                .region(Region.of("us-east-1")).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv("RM_RUSTFS_E2E_ACCESS"),
                        System.getenv("RM_RUSTFS_E2E_SECRET")))).build();
    }

    @Test void seedBeforeContainerRestart() throws Exception {
        Assumptions.assumeTrue("seed".equals(System.getenv("RM_RUSTFS_E2E_PHASE")));
        byte[] content=new byte[128*1024+11];
        new java.security.SecureRandom().nextBytes(content);
        System.arraycopy("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII),0,
                content,0,9);
        UUID tenant=UUID.randomUUID(), file=UUID.randomUUID();
        try(var api=client()) {
            var rust=new RustFsFileStorage(api,System.getenv("RM_RUSTFS_E2E_BUCKET"));
            var staged=rust.stage(tenant,file,new ByteArrayInputStream(content),
                    content.length,"application/pdf");
            String dest=rust.promote(tenant,file,staged);
            assertThat(rust.matches(dest,content.length,staged.sha256())).isTrue();
            Properties props=new Properties();
            props.setProperty("tenant",tenant.toString());
            props.setProperty("file",file.toString());
            props.setProperty("key",dest);
            props.setProperty("size",Integer.toString(content.length));
            props.setProperty("sha",staged.sha256());
            Files.createDirectories(CHECKPOINT.getParent());
            try(var out=Files.newOutputStream(CHECKPOINT)){props.store(out,"RustFS daemon restart checkpoint");}
        }
    }

    @Test void verifyAfterContainerRestart() throws Exception {
        Assumptions.assumeTrue("verify".equals(System.getenv("RM_RUSTFS_E2E_PHASE")));
        assertThat(Files.isRegularFile(CHECKPOINT)).as("Required seed from previous Maven process").isTrue();
        Properties props=new Properties();
        try(var in=Files.newInputStream(CHECKPOINT)){props.load(in);}
        UUID tenant=UUID.fromString(props.getProperty("tenant"));
        UUID file=UUID.fromString(props.getProperty("file"));
        String key=props.getProperty("key"), sha=props.getProperty("sha");
        int size=Integer.parseInt(props.getProperty("size"));
        try(var api=client()) {
            var rust=new RustFsFileStorage(api,System.getenv("RM_RUSTFS_E2E_BUCKET"));
            assertThat(rust.matches(key,size,sha)).as("bytes retained across real daemon restart").isTrue();
            var copy=new ByteArrayOutputStream();
            rust.streamVerified(tenant,file,key,copy);
            assertThat(copy.size()).isEqualTo(size);
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(copy.toByteArray()))).isEqualTo(sha);
            rust.delete(key);
            assertThat(rust.exists(key)).isFalse();
        }
        Files.delete(CHECKPOINT);
    }
}
