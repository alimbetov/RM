package kz.rm.files;

import kz.rm.security.kms.KeyStatus;
import kz.rm.security.kms.KeyWrappingService;
import kz.rm.security.kms.TenantKeyEntity;
import kz.rm.security.kms.TenantKeyService;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FileContentCryptoQualificationTest {
    private record Fixture(AesGcmFileContentCrypto crypto,UUID tenant,UUID file){}
    private Fixture fixture() {
        UUID tenant=UUID.randomUUID();
        byte[] root=new byte[32];
        for(int i=0;i<root.length;i++)root[i]=(byte)(i+1);
        var wrapping=new KeyWrappingService(Base64.getEncoder().encodeToString(root));
        var secret=wrapping.generateTenantKey();
        var slot=new TenantKeyEntity.Slot(1L,KeyStatus.ACTIVE,
                wrapping.wrap(tenant,1,secret),"root-v1");
        var keys=mock(TenantKeyService.class);
        when(keys.active(tenant)).thenReturn(slot);
        when(keys.forDecrypt(tenant,1)).thenReturn(slot);
        return new Fixture(new AesGcmFileContentCrypto(keys,wrapping),tenant,UUID.randomUUID());
    }
    private byte[] encrypt(Fixture f,byte[] clear) throws Exception {
        var out=new ByteArrayOutputStream();
        f.crypto().encrypt(f.tenant(),f.file(),new ByteArrayInputStream(clear),out);
        return out.toByteArray();
    }
    private byte[] decrypt(Fixture f,byte[] encoded) throws Exception {
        var out=new ByteArrayOutputStream();
        f.crypto().decrypt(f.tenant(),f.file(),new ByteArrayInputStream(encoded),out);
        return out.toByteArray();
    }
    @Test void streamRoundtripUsesAuthenticatedChunksAndNoPlaintextPrefix() throws Exception {
        var f=fixture();
        byte[] clear=new byte[150_001];
        new java.security.SecureRandom().nextBytes(clear);
        byte[] cipher=encrypt(f,clear);
        assertThat(cipher.length).isGreaterThan(clear.length);
        assertThat(java.util.Arrays.equals(java.util.Arrays.copyOfRange(cipher,28,44),
                java.util.Arrays.copyOf(clear,16))).isFalse();
        assertThat(decrypt(f,cipher)).isEqualTo(clear);
    }
    @Test void mutationTruncationWrongFileAndWrongTenantFailClosed() throws Exception {
        var f=fixture();
        byte[] clear=new byte[100_000];new java.security.SecureRandom().nextBytes(clear);
        byte[] cipher=encrypt(f,clear);
        byte[] corrupted=cipher.clone();
        corrupted[corrupted.length-25]^=0x40;
        assertThatThrownBy(()->decrypt(f,corrupted)).isInstanceOf(IOException.class);
        byte[] truncated=java.util.Arrays.copyOf(cipher,cipher.length-15);
        assertThatThrownBy(()->decrypt(f,truncated)).isInstanceOf(IOException.class);
        var wrongFile=new ByteArrayOutputStream();
        assertThatThrownBy(()->f.crypto().decrypt(f.tenant(),UUID.randomUUID(),
                new ByteArrayInputStream(cipher),wrongFile)).isInstanceOf(IOException.class);
        var wrongTenant=new ByteArrayOutputStream();
        assertThatThrownBy(()->f.crypto().decrypt(UUID.randomUUID(),f.file(),
                new ByteArrayInputStream(cipher),wrongTenant)).isInstanceOf(RuntimeException.class);
    }
    @Test void emptyFileStillHasAuthenticatedTerminalRecord() throws Exception {
        var f=fixture();
        byte[] cipher=encrypt(f,new byte[0]);
        assertThat(decrypt(f,cipher)).isEmpty();
        assertThatThrownBy(()->decrypt(f,java.util.Arrays.copyOf(cipher,cipher.length-1)))
                .isInstanceOf(IOException.class);
    }
}
