package kz.rm.files;

import kz.rm.security.kms.*;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.mockito.Mockito.*;

/** Deterministic SYNTHETIC tenant DEK for isolated daemon restart tests only.
 * Actual PostgreSQL-backed tenant KMS is qualified in the separate PG journey. */
final class V1DaemonCryptoFixture {
    private V1DaemonCryptoFixture(){}
    static FileContentCryptoPort crypto(UUID tenant)throws Exception{
        var wrapping=new KeyWrappingService(Base64.getEncoder().encodeToString(new byte[32]));
        var synthetic=new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(
            ("RM-TEST-ONLY-RUSTFS-RESTART:"+tenant).getBytes(StandardCharsets.US_ASCII)),"AES");
        var slot=new TenantKeyEntity.Slot(1L,KeyStatus.ACTIVE,wrapping.wrap(tenant,1,synthetic),"root-v1");
        var keys=mock(TenantKeyService.class);
        when(keys.active(tenant)).thenReturn(slot);when(keys.forDecrypt(tenant,1L)).thenReturn(slot);
        return new FileContentCryptoService(keys,wrapping);
    }
    static FileContentCryptoPort.Expected expected(UUID tenant,UUID file,UUID attempt,byte[] bytes)throws Exception{
        return new FileContentCryptoPort.Expected(tenant,file,attempt,FileContentCryptoPort.Format.PDF,
            bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
    }
}
