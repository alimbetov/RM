package kz.rm.files;

import kz.rm.security.kms.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecureLocalStorageQualificationTest {
    @TempDir Path root;
    private record Setup(UUID tenant,UUID file,SecureLocalFileStorage storage){}
    private Setup setup() throws Exception {
        UUID tenant=UUID.randomUUID(),file=UUID.randomUUID();
        var rootKey=new byte[32];new java.security.SecureRandom().nextBytes(rootKey);
        var wrapping=new KeyWrappingService(Base64.getEncoder().encodeToString(rootKey));
        var key=wrapping.generateTenantKey();
        var slot=new TenantKeyEntity.Slot(1L,KeyStatus.ACTIVE,
                wrapping.wrap(tenant,1,key),"root-v1");
        var keys=mock(TenantKeyService.class);
        when(keys.active(tenant)).thenReturn(slot);
        when(keys.forDecrypt(tenant,1L)).thenReturn(slot);
        var crypto=new AesGcmFileContentCrypto(keys,wrapping);
        return new Setup(tenant,file,new SecureLocalFileStorage(root.toString(),crypto));
    }
    private byte[] sample() {
        var bytes=new byte[100_000];new java.security.SecureRandom().nextBytes(bytes);
        byte[] head="%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(head,0,bytes,0,head.length);
        return bytes;
    }
    @Test void stagingIsEncryptedAndScannerReadsAuthenticatedPlaintextWithoutDiskLeak()
            throws Exception {
        var s=setup();byte[] original=sample();
        var staged=s.storage().stage(s.tenant(),s.file(),
                new ByteArrayInputStream(original),original.length,"application/pdf");
        assertThat(staged.cryptoVersion()).isEqualTo(1);
        Path onDisk=root.resolve(staged.key());
        byte[] disk=Files.readAllBytes(onDisk);
        assertThat(disk.length).isGreaterThan(original.length);
        assertThat(Arrays.copyOf(disk,4)).isEqualTo(new byte[]{'R','M','F','C'});
        assertThat(Arrays.copyOf(disk,original.length)).isNotEqualTo(original);
        try(var scanned=s.storage().openStagedForScan(staged.key())){
            assertThat(scanned.readAllBytes()).isEqualTo(original);
        }
        String finalKey=s.storage().promote(s.tenant(),s.file(),staged);
        assertThat(s.storage().matches(finalKey,staged.sizeBytes(),staged.sha256())).isTrue();
        var copy=new ByteArrayOutputStream();
        s.storage().streamVerified(s.tenant(),s.file(),finalKey,copy);
        assertThat(copy.toByteArray()).isEqualTo(original);
        assertThatThrownBy(()->s.storage().streamVerified(s.tenant(),UUID.randomUUID(),
                finalKey,new ByteArrayOutputStream())).isInstanceOf(SecurityException.class);
    }
    @Test void ciphertextTamperFailsIntegrityAndCannotBeRead() throws Exception {
        var s=setup();byte[] original=sample();
        var staged=s.storage().stage(s.tenant(),s.file(),
                new ByteArrayInputStream(original),original.length,"application/pdf");
        String finalKey=s.storage().promote(s.tenant(),s.file(),staged);
        Path path=root.resolve(finalKey);
        byte[] disk=Files.readAllBytes(path);
        disk[disk.length-20]^=0x40;
        Files.write(path,disk);
        assertThat(s.storage().matches(finalKey,staged.sizeBytes(),staged.sha256())).isFalse();
        assertThatThrownBy(()->s.storage().streamVerified(s.tenant(),s.file(),finalKey,
                new ByteArrayOutputStream())).isInstanceOf(IOException.class);
    }
    @Test void plaintextLegacyAdapterRefusesSecureContentRead() throws Exception {
        var legacy=new LocalFileStorage(root.toString());
        assertThatThrownBy(()->legacy.streamVerified(UUID.randomUUID(),UUID.randomUUID(),
                "not-a-key",new ByteArrayOutputStream())).isInstanceOf(SecurityException.class);
    }
}
