package kz.rm.files;

import kz.rm.security.kms.FileContentCryptoPort;
import kz.rm.security.kms.FileContentCryptoPort.Expected;
import kz.rm.security.kms.FileContentCryptoPort.Format;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** FILES-owned trusted V1 storage operations, not an upload or download API.
 * No plaintext spool; caller must authorize immutable intent and scan before
 * committing AVAILABLE. Per-JVM conservative admission: one V1 operation at
 * a time. Multi-pod/tenant budgets and complete lifecycle remain release gates. */
@Service
final class ReportEncryptedStorage {
    private final FileStoragePort storage;
    private final FileContentCryptoPort crypto;
    private final Semaphore memory = new Semaphore(1,true);

    ReportEncryptedStorage(FileStoragePort storage,FileContentCryptoPort crypto) {
        this.storage=storage;this.crypto=crypto;
    }
    @FunctionalInterface interface PlaintextReader<T> {
        T read(InputStream authenticated) throws IOException;
    }
    String stage(Expected expected,InputStream source) throws IOException {
        if(source==null)throw new IllegalArgumentException("REPORT_SOURCE_REQUIRED");
        acquire();
        byte[] plain=null;
        try {
            plain=source.readNBytes(expected.sizeBytes()+1);
            if(plain.length!=expected.sizeBytes())throw new FileContentCryptoPort.IntegrityFailure();
            byte[] envelope=crypto.seal(expected,plain);
            return storage.stageEncrypted(expected,envelope);
        } finally {
            if(plain!=null)Arrays.fill(plain,(byte)0);
            memory.release();
        }
    }
    String promote(Expected expected,String stagingKey) throws IOException {
        // Provider promotion copies ciphertext only. Caller must independently
        // verify final bytes before committing the DB pointer.
        acquire();
        try {return storage.promoteEncrypted(expected,stagingKey);}
        finally {memory.release();}
    }
    <T> T read(Expected expected,String key,boolean staged,PlaintextReader<T> reader) throws IOException {
        acquire();
        byte[] plain=null;
        try {
            int size=FileContentCryptoPort.envelopeSize(expected);
            byte[] envelope;
            try(InputStream in=storage.openEncrypted(expected,key,staged)) {
                envelope=in.readNBytes(size+1);
            }
            if(envelope.length!=size)throw new FileContentCryptoPort.IntegrityFailure();
            plain=crypto.open(expected,envelope);
            // The consumer receives only authenticated bytes, with ownership
            // bounded to this synchronous callback. No generic V0 stream.
            try(InputStream in=new ByteArrayInputStream(plain)) {return reader.read(in);}
        } finally {
            if(plain!=null)Arrays.fill(plain,(byte)0);
            memory.release();
        }
    }
    boolean matches(Expected expected,String key,boolean staged) throws IOException {
        try {return read(expected,key,staged,in->true);}
        catch(FileNotFoundException | FileContentCryptoPort.IntegrityFailure e){return false;}
        // CryptoUnavailable/IO failures propagate; never label an outage corruption.
    }
    static Expected persisted(UUID tenant,UUID file,String key,String mime,long size,String sha) throws IOException {
        Format format=switch(mime) {
            case "application/pdf" -> Format.PDF;
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Format.XLSX;
            default -> throw new IOException("REPORT_ENCRYPTED_MIME");
        };
        return new Expected(tenant,file,ReportStorageKey.attempt(key,tenant,file,false),format,
                Math.toIntExact(size),sha);
    }
    private void acquire() throws IOException {
        if(!memory.tryAcquire())throw new IOException("REPORT_ENCRYPTED_STORAGE_BUSY");
    }
}
