package kz.rm.files;

import kz.rm.security.kms.FileContentCryptoPort;
import kz.rm.security.kms.FileContentCryptoService;
import kz.rm.security.kms.KeyStatus;
import kz.rm.security.kms.KeyWrappingService;
import kz.rm.security.kms.TenantKeyEntity;
import kz.rm.security.kms.TenantKeyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static kz.rm.security.kms.FileContentCryptoPort.*;

class ReportEncryptedStorageQualificationTest {
    @TempDir Path root;
    private final byte[] plain="%PDF-1.7\nsynthetic confidential report\n".getBytes(StandardCharsets.US_ASCII);

    private record Fixture(Expected expected,LocalFileStorage local,ReportEncryptedStorage storage,
                           TenantKeyService keys) {}
    private Fixture fixture() throws Exception {
        Expected e=new Expected(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),Format.PDF,
            plain.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plain)));
        var wrapping=new KeyWrappingService(Base64.getEncoder().encodeToString(new byte[32]));
        var wrapped=wrapping.wrap(e.tenantId(),1,wrapping.generateTenantKey());
        var slot=new TenantKeyEntity.Slot(1L,KeyStatus.ACTIVE,wrapped,"root-v1");
        var keys=mock(TenantKeyService.class);
        when(keys.active(e.tenantId())).thenReturn(slot);
        when(keys.forDecrypt(e.tenantId(),1L)).thenReturn(slot);
        var local=new LocalFileStorage(root.toString());
        return new Fixture(e,local,new ReportEncryptedStorage(local,new FileContentCryptoService(keys,wrapping)),keys);
    }

    @Test void v1StageAndFinalAreCiphertextAndLegacyReadsDeny() throws Exception {
        var f=fixture();var e=f.expected();
        String stage=f.storage().stage(e,new ByteArrayInputStream(plain));
        byte[] raw=Files.readAllBytes(root.resolve(stage));
        assertEquals(FileContentCryptoPort.envelopeSize(e),raw.length);
        assertFalse(new String(raw,StandardCharsets.ISO_8859_1).contains(new String(plain,StandardCharsets.US_ASCII)));
        assertArrayEquals(plain,f.storage().read(e,stage,true,InputStream::readAllBytes));
        assertThrows(IOException.class,()->f.local().openStagedForScan(stage));
        assertThrows(IOException.class,()->f.local().matches(stage,e.sizeBytes(),e.sha256()));
        assertThrows(IOException.class,()->f.storage().stage(e,new ByteArrayInputStream(plain)));
        assertArrayEquals(raw,Files.readAllBytes(root.resolve(stage)));
        String dest=f.storage().promote(e,stage);
        assertFalse(f.local().exists(stage));
        assertArrayEquals(raw,Files.readAllBytes(root.resolve(dest)));
        assertTrue(f.storage().matches(e,dest,false));
        assertEquals(e,ReportEncryptedStorage.persisted(e.tenantId(),e.fileId(),dest,"application/pdf",e.sizeBytes(),e.sha256()));
        assertThrows(IOException.class,()->f.local().streamVerified(e.tenantId(),e.fileId(),dest,new ByteArrayOutputStream()));
        assertEquals(List.of(dest),f.local().listKeys(e.tenantId(),e.fileId()));
        var restarted=new LocalFileStorage(root.toString());
        assertTrue(restarted.exists(dest));
    }

    @Test void corruptBytesNeverReachReaderAndKmsOutageIsNotCorruption() throws Exception {
        var f=fixture();var e=f.expected();
        String dest=f.storage().promote(e,f.storage().stage(e,new ByteArrayInputStream(plain)));
        when(f.keys().forDecrypt(e.tenantId(),1L)).thenThrow(new IllegalStateException("synthetic outage"));
        assertThrows(CryptoUnavailable.class,()->f.storage().matches(e,dest,false));
        verify(f.keys()).forDecrypt(e.tenantId(),1L);
        // Restore via a separate fixture to isolate the deliberate KMS failure.
        var g=fixture();var x=g.expected();
        String damaged=g.storage().promote(x,g.storage().stage(x,new ByteArrayInputStream(plain)));
        byte[] raw=Files.readAllBytes(root.resolve(damaged));raw[raw.length-1]^=1;
        Files.write(root.resolve(damaged),raw);
        var called=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(IntegrityFailure.class,()->g.storage().read(x,damaged,false,in->{called.set(true);return true;}));
        assertFalse(called.get());
        assertFalse(g.storage().matches(x,damaged,false));
        var foreign=new Expected(UUID.randomUUID(),x.fileId(),x.uploadAttempt(),x.format(),x.sizeBytes(),x.sha256());
        assertThrows(IOException.class,()->g.storage().read(foreign,damaged,false,InputStream::readAllBytes));
    }

    @Test void v0StillStagesPromotesAndReadsWithOriginalHash() throws Exception {
        var f=fixture();var e=f.expected();
        var staged=f.local().stage(e.tenantId(),e.fileId(),new ByteArrayInputStream(plain),plain.length,"application/pdf");
        assertEquals(0,staged.cryptoVersion());
        String dest=f.local().promote(e.tenantId(),e.fileId(),staged);
        assertTrue(f.local().matches(dest,plain.length,e.sha256()));
        var out=new ByteArrayOutputStream();f.local().streamVerified(e.tenantId(),e.fileId(),dest,out);
        assertArrayEquals(plain,out.toByteArray());
    }
}
