package kz.rm.files;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opt-in encrypted LOCAL adapter. No plaintext ever written to disk: ciphertext
 * is emitted directly from a streaming AES-GCM frame encoder.
 * LOCAL remains single-node UAT only until backup/power-loss qualification.
 */
@Component
@Primary
@ConditionalOnProperty(name="rm.files.encrypted-local.enabled",havingValue="true")
class SecureLocalFileStorage implements FileStoragePort {
    private final Path root;
    private final FileContentCryptoPort crypto;

    SecureLocalFileStorage(@Value("${rm.files.local-root:${java.io.tmpdir}/rm-files-synthetic}") String configured,
                           FileContentCryptoPort crypto) throws IOException {
        if(crypto==null)throw new IllegalArgumentException("Binary crypto required");
        this.crypto=crypto;
        root=Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(root);
        if(Files.isSymbolicLink(root) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe encrypted LOCAL root");
        try {
            Files.setPosixFilePermissions(root,Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE));
        } catch(UnsupportedOperationException ignored){}
    }

    private Path resolve(String key) throws IOException {
        if(key==null || !key.matches("[0-9a-f-]{36}/[0-9a-f-]{36}/(stage|final)-[0-9a-f-]{36}"))
            throw new IOException("Invalid private storage reference");
        Path path=root.resolve(key).normalize();
        if(!path.startsWith(root))throw new IOException("Unsafe private storage reference");
        Path parent=path.getParent();
        if(Files.isSymbolicLink(parent) || Files.isSymbolicLink(parent.getParent()))
            throw new IOException("Symlink namespace forbidden");
        return path;
    }
    private void prepare(UUID tenant,UUID file) throws IOException {
        Path dir=root.resolve(tenant.toString()).resolve(file.toString());
        Files.createDirectories(dir);
        if(Files.isSymbolicLink(dir)||Files.isSymbolicLink(dir.getParent()))
            throw new IOException("Unsafe encrypted storage namespace");
    }
    private static MessageDigest digest() throws IOException {
        try{return MessageDigest.getInstance("SHA-256");}
        catch(Exception e){throw new IOException("SHA-256 unavailable",e);}
    }
    private static class TrackingInput extends FilterInputStream {
        final long expected;
        final MessageDigest sha;
        final byte[] prefix=new byte[16];
        int prefixSize;
        long length;
        TrackingInput(InputStream in,long expected,MessageDigest sha){
            super(in);this.expected=expected;this.sha=sha;
        }
        @Override public int read(byte[] bytes,int off,int len) throws IOException {
            int count=in.read(bytes,off,len);
            if(count>0) {
                length+=count;
                if(length>expected)throw new IOException("Upload exceeds declared size");
                sha.update(bytes,off,count);
                if(prefixSize<prefix.length) {
                    int take=Math.min(count,prefix.length-prefixSize);
                    System.arraycopy(bytes,off,prefix,prefixSize,take);
                    prefixSize+=take;
                }
            }
            return count;
        }
        @Override public int read() throws IOException {
            int b=in.read();
            if(b>=0) {
                length++;
                if(length>expected)throw new IOException("Upload exceeds declared size");
                sha.update((byte)b);
                if(prefixSize<prefix.length)prefix[prefixSize++]=(byte)b;
            }
            return b;
        }
    }

    @Override public Staged stage(UUID tenant,UUID file,InputStream plaintext,
                                  long expectedSize,String mime) throws IOException {
        if(tenant==null || file==null || plaintext==null || expectedSize<1)
            throw new IOException("Invalid protected upload");
        prepare(tenant,file);
        String key=tenant+"/"+file+"/stage-"+UUID.randomUUID();
        Path path=resolve(key);
        MessageDigest digest=digest();
        var tracked=new TrackingInput(plaintext,expectedSize,digest);
        boolean completed=false;
        try(OutputStream cipher=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            crypto.encrypt(tenant,file,tracked,cipher);
            if(tracked.length!=expectedSize)throw new IOException("Upload truncated");
            FileContentInspection.requireExpected(mime,tracked.prefix,tracked.prefixSize);
            cipher.flush();
            completed=true;
        } finally {
            if(!completed)Files.deleteIfExists(path);
        }
        return new Staged(key,expectedSize,HexFormat.of().formatHex(digest.digest()),mime,1);
    }

    private InputStream decrypting(String key,UUID tenant,UUID file) throws IOException {
        Path p=resolve(key);
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Encrypted staging content unavailable");
        var pipe=new PipedInputStream(64*1024);
        var output=new PipedOutputStream(pipe);
        var failure=new AtomicReference<IOException>();
        Thread task=Thread.ofVirtual().name("rm-file-decrypt").start(()->{
            try(InputStream encrypted=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS);
                OutputStream decrypted=output) {
                crypto.decrypt(tenant,file,encrypted,decrypted);
            } catch(IOException ex) {
                failure.set(ex);
                try {output.close();}catch(IOException ignored){}
            }
        });
        return new FilterInputStream(pipe){
            private void check(int n) throws IOException {
                if(n==-1 && failure.get()!=null)
                    throw new IOException("Encrypted staged content failed authentication",failure.get());
            }
            @Override public int read() throws IOException {
                int n=super.read();check(n);return n;
            }
            @Override public int read(byte[] b,int off,int len) throws IOException {
                int n=super.read(b,off,len);check(n);return n;
            }
            @Override public void close() throws IOException {
                super.close();task.interrupt();
            }
        };
    }
    @Override public InputStream openStagedForScan(String key) throws IOException {
        if(key==null || !key.matches("[0-9a-f-]{36}/[0-9a-f-]{36}/stage-[0-9a-f-]{36}"))
            throw new IOException("Scanner may inspect staging only");
        String[] parts=key.split("/");
        return decrypting(key,UUID.fromString(parts[0]),UUID.fromString(parts[1]));
    }
    @Override public String promote(UUID tenant,UUID file,Staged staged) throws IOException {
        if(staged.cryptoVersion()!=1 || staged.key()==null
                || !staged.key().startsWith(tenant+"/"+file+"/stage-"))
            throw new IOException("Invalid encrypted staging owner");
        String key=tenant+"/"+file+"/final-"+UUID.randomUUID();
        Files.move(resolve(staged.key()),resolve(key),StandardCopyOption.ATOMIC_MOVE);
        return key;
    }
    @Override public boolean exists(String key) throws IOException {
        return Files.isRegularFile(resolve(key),LinkOption.NOFOLLOW_LINKS);
    }
    @Override public boolean matches(String key,long expectedSize,String expectedHash) throws IOException {
        if(expectedSize<0||expectedHash==null||!expectedHash.matches("[0-9a-f]{64}"))
            return false;
        String[] parts=key.split("/");
        if(parts.length!=3)return false;
        MessageDigest sha=digest();
        class CountedOutput extends OutputStream {
            long count;
            @Override public void write(int b) throws IOException {
                if(++count>expectedSize)throw new IOException("Excess plaintext");
                sha.update((byte)b);
            }
            @Override public void write(byte[] b,int off,int len) throws IOException {
                count+=len;
                if(count>expectedSize)throw new IOException("Excess plaintext");
                sha.update(b,off,len);
            }
        }
        var count=new CountedOutput();
        try(InputStream encrypted=Files.newInputStream(resolve(key),LinkOption.NOFOLLOW_LINKS)){
            crypto.decrypt(UUID.fromString(parts[0]),UUID.fromString(parts[1]),
                    encrypted,count);
        } catch(IOException | RuntimeException e) {
            return false;
        }
        return count.count==expectedSize && MessageDigest.isEqual(
                HexFormat.of().formatHex(sha.digest()).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    @Override public void streamVerified(UUID tenant,UUID file,String key,OutputStream target)
            throws IOException {
        if(target==null || tenant==null || file==null || key==null
                || !key.startsWith(tenant+"/"+file+"/final-"))
            throw new SecurityException("Invalid trusted binary read scope");
        try(InputStream encrypted=Files.newInputStream(resolve(key),LinkOption.NOFOLLOW_LINKS)){
            crypto.decrypt(tenant,file,encrypted,target);
        }
    }
    @Override public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }
    @Override public List<String> listKeys(UUID tenant,UUID file) throws IOException {
        if(tenant==null || file==null)throw new IOException("Tenant/file required");
        Path dir=root.resolve(tenant.toString()).resolve(file.toString()).normalize();
        if(!dir.startsWith(root)||Files.isSymbolicLink(dir)
                || Files.isSymbolicLink(dir.getParent()))
            throw new IOException("Unsafe recovery namespace");
        if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return List.of();
        var found=new ArrayList<String>();
        try(var paths=Files.newDirectoryStream(dir)){
            for(Path p:paths) {
                if(found.size()>=10000)throw new IOException("Too many recovery candidates");
                String n=p.getFileName().toString();
                if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)
                        && n.matches("(stage|final)-[0-9a-f-]{36}"))
                    found.add(tenant+"/"+file+"/"+n);
            }
        }
        return found;
    }
}
