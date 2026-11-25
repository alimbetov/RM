package kz.rm.files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No live RustFS daemon: test S3 client calls, switch routing, and local failover. */
class RustFsStorageQualificationTest {
    @TempDir Path root;

    @Test void sdkStorageStagesChecksLengthCopiesDeletesAndListsPrivateObjects() throws Exception {
        S3Client s3=mock(S3Client.class);
        byte[] bytes="%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        when(s3.putObject(any(PutObjectRequest.class),any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength((long)bytes.length).build());
        when(s3.copyObject(any(CopyObjectRequest.class)))
                .thenReturn(CopyObjectResponse.builder().build());
        when(s3.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());
        UUID tenant=UUID.randomUUID(),file=UUID.randomUUID();
        RustFsFileStorage backend=new RustFsFileStorage(s3,"rm-files");
        var staged=backend.stage(tenant,file,new ByteArrayInputStream(bytes),
                bytes.length,"application/pdf");
        assertThat(staged.key()).startsWith("rustfs/"+tenant+"/"+file+"/stage-");
        assertThat(staged.cryptoVersion()).isZero();
        assertThat(staged.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)));
        var complete=backend.promote(tenant,file,staged);
        assertThat(complete).startsWith("rustfs/"+tenant+"/"+file+"/final-");
        verify(s3).copyObject(any(CopyObjectRequest.class));
        verify(s3).deleteObject(org.mockito.ArgumentMatchers.<DeleteObjectRequest>argThat(x->x.key().contains("/stage-")));
        var keys=ListObjectsV2Response.builder().contents(
                S3Object.builder().key(staged.key().substring(7)).build(),
                S3Object.builder().key(complete.substring(7)).build()).isTruncated(false).build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(keys);
        assertThat(backend.listKeys(tenant,file)).containsExactlyInAnyOrder(staged.key(),complete);
        assertThatThrownBy(()->backend.promote(UUID.randomUUID(),file,staged))
                .isInstanceOf(IOException.class);
    }

    @Test void wrongSignatureNeverUploadsAndS3OutageIsNotAHashMismatch() throws Exception {
        S3Client s3=mock(S3Client.class);
        RustFsFileStorage backend=new RustFsFileStorage(s3,"rm-files");
        UUID tenant=UUID.randomUUID(),file=UUID.randomUUID();
        assertThatThrownBy(()->backend.stage(tenant,file,new ByteArrayInputStream(new byte[16]),
                16,"application/pdf")).isInstanceOf(IOException.class);
        verify(s3,never()).putObject(any(PutObjectRequest.class),any(RequestBody.class));
        String key="rustfs/"+tenant+"/"+file+"/final-"+UUID.randomUUID();
        when(s3.getObject(any(GetObjectRequest.class))).thenThrow(
                S3Exception.builder().statusCode(503).message("temporarily unavailable").build());
        assertThatThrownBy(()->backend.matches(key,4,"a".repeat(64)))
                .isInstanceOf(IOException.class).hasMessageContaining("unavailable");
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(
                S3Exception.builder().statusCode(503).build());
        assertThatThrownBy(()->backend.exists(key)).isInstanceOf(IOException.class);
    }

    @Test void switchWritesToSelectedBackendAndKeepsLegacyLocalKeys() throws Exception {
        LocalFileStorage local=new LocalFileStorage(root.toString());
        RustFsFileStorage rust=mock(RustFsFileStorage.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<RustFsFileStorage> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(rust);
        UUID tenant=UUID.randomUUID(),file=UUID.randomUUID();
        byte[] pdf="%PDF-1.4\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        var localBlob=local.stage(tenant,file,new ByteArrayInputStream(pdf),
                pdf.length,"application/pdf");
        var rustBlob=new FileStoragePort.Staged(
                "rustfs/"+tenant+"/"+file+"/stage-"+UUID.randomUUID(),
                pdf.length,"f".repeat(64),"application/pdf",0);
        when(rust.stage(eq(tenant),eq(file),any(InputStream.class),eq((long)pdf.length),
                eq("application/pdf"))).thenReturn(rustBlob);
        var switchOn=new SwitchableFileStorage(local,provider,true,false);
        assertThat(switchOn.stage(tenant,file,new ByteArrayInputStream(pdf),
                pdf.length,"application/pdf")).isEqualTo(rustBlob);
        assertThat(switchOn.exists(localBlob.key())).isTrue();
        verify(rust,never()).exists(localBlob.key());
        var switchOff=new SwitchableFileStorage(local,provider,false,false);
        assertThat(switchOff.stage(tenant,file,new ByteArrayInputStream(pdf),
                pdf.length,"application/pdf").key()).doesNotStartWith("rustfs/");
        verify(rust,times(1)).stage(eq(tenant),eq(file),any(InputStream.class),
                eq((long)pdf.length),eq("application/pdf"));
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(()->new SwitchableFileStorage(local,provider,true,false))
                .isInstanceOf(IllegalStateException.class);
    }
}
