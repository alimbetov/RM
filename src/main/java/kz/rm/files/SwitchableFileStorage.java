package kz.rm.files;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.*;
import java.util.*;

/**
 * The flag changes placement of NEW uploads, never the location of existing
 * objects. Persisted "rustfs/" key namespace routes reads/cleanup back to S3.
 */
@Component
@Primary
class SwitchableFileStorage implements FileStoragePort {
    private final LocalFileStorage local;
    private final RustFsFileStorage rust;
    private final boolean useRust;

    SwitchableFileStorage(LocalFileStorage local,
                          ObjectProvider<RustFsFileStorage> rustProvider,
                          @Value("${rm.files.rustfs.enabled:false}") boolean useRust,
                          @Value("${rm.files.encrypted-local.enabled:false}") boolean encryptedLocal) {
        if(encryptedLocal)
            throw new IllegalStateException("FILES application-level encryption is disabled by RM policy");
        this.local=local;
        this.rust=rustProvider.getIfAvailable();
        this.useRust=useRust;
        if(useRust && this.rust==null)
            throw new IllegalStateException("RustFS enabled but endpoint/credentials not configured");
    }
    private FileStoragePort existing(String key) throws IOException {
        if(key==null)throw new IOException("Storage key required");
        if(key.startsWith("rustfs/")) {
            if(rust==null)throw new IOException("RustFS object exists but RustFS endpoint is not configured");
            if(!RustFsFileStorage.isRustKey(key))
                throw new IOException("Invalid RustFS storage key");
            return rust;
        }
        return local;
    }
    @Override public Staged stage(UUID tenant,UUID file,InputStream data,
                                  long size,String mime) throws IOException {
        return (useRust?rust:local).stage(tenant,file,data,size,mime);
    }
    @Override public String promote(UUID tenant,UUID file,Staged staged) throws IOException {
        if(staged==null)throw new IOException("Staged reference required");
        return existing(staged.key()).promote(tenant,file,staged);
    }
    @Override public InputStream openStagedForScan(String key) throws IOException {
        return existing(key).openStagedForScan(key);
    }
    @Override public boolean exists(String key) throws IOException {
        return existing(key).exists(key);
    }
    @Override public boolean matches(String key,long bytes,String hash) throws IOException {
        return existing(key).matches(key,bytes,hash);
    }
    @Override public void streamVerified(UUID tenant,UUID file,String key,OutputStream sink)
            throws IOException {
        existing(key).streamVerified(tenant,file,key,sink);
    }
    @Override public void delete(String key) throws IOException {
        existing(key).delete(key);
    }
    @Override public List<String> listKeys(UUID tenant,UUID file) throws IOException {
        List<String> found=new ArrayList<>(local.listKeys(tenant,file));
        if(rust!=null)found.addAll(rust.listKeys(tenant,file));
        if(found.size()>10000)throw new IOException("Recovery candidate limit exceeded");
        return found;
    }
}
