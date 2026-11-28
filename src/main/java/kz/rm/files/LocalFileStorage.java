package kz.rm.files;

import org.springframework.beans.factory.annotation.Value;
import kz.rm.security.kms.FileContentCryptoPort;
import kz.rm.security.kms.FileContentCryptoPort.Expected;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Private single-node LOCAL adapter, synthetic files only until encryption/AV gates. */
@Component
class LocalFileStorage implements FileStoragePort {
    private final Path root;
    LocalFileStorage(@Value("${rm.files.local-root:${java.io.tmpdir}/rm-files-synthetic}") String configured)
            throws IOException {
        root=Path.of(configured).toAbsolutePath().normalize();
        Files.createDirectories(root);
        if(Files.isSymbolicLink(root) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe local file root");
        try {
            Files.setPosixFilePermissions(root,Set.of(
                    PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch(UnsupportedOperationException ignored) {
            // Non-POSIX platforms must restrict the configured root externally.
        }
    }
    private Path resolve(String key) throws IOException {
        if(key==null || !(key.matches("[0-9a-f-]{36}/[0-9a-f-]{36}/(stage|final)-[0-9a-f-]{36}") || key.matches(ReportStorageKey.LOCAL_PATTERN)))
            throw new IOException("Invalid storage reference");
        Path path=root.resolve(key).normalize();
        if(!path.startsWith(root))throw new IOException("Unsafe storage reference");
        return path;
    }
    private Path bucket(UUID tenant,UUID id) throws IOException {
        Path namespace=root.resolve(tenant.toString()).resolve(id.toString());
        if(!namespace.normalize().startsWith(root))throw new IOException("Unsafe namespace");
        Files.createDirectories(namespace);
        if(Files.isSymbolicLink(namespace)
                || Files.isSymbolicLink(namespace.getParent()))
            throw new IOException("Symlink storage namespace denied");
        return namespace;
    }
    @Override public Staged stage(UUID tenant,UUID fileId,InputStream in,
                                  long declaredSize,String mime) throws IOException {
        if(in==null || tenant==null || fileId==null || declaredSize<=0)
            throw new IOException("Invalid staged upload");
        bucket(tenant,fileId);
        String key=tenant+"/"+fileId+"/stage-"+UUID.randomUUID();
        Path path=resolve(key);
        long bytes=0;
        MessageDigest sha;
        try {sha=MessageDigest.getInstance("SHA-256");}
        catch(Exception ex){throw new IOException("SHA-256 unavailable",ex);}
        byte[] prefix=new byte[16];
        int prefixSize=0;
        boolean success=false;
        try(OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer=new byte[64*1024];
            int n;
            while((n=in.read(buffer))!=-1) {
                if(n==0)continue;
                bytes+=n;
                if(bytes>declaredSize)throw new IOException("Upload exceeds declared size");
                if(prefixSize<prefix.length) {
                    int copy=Math.min(n,prefix.length-prefixSize);
                    System.arraycopy(buffer,0,prefix,prefixSize,copy);
                    prefixSize+=copy;
                }
                sha.update(buffer,0,n);
                out.write(buffer,0,n);
            }
            if(bytes!=declaredSize)throw new IOException("Incomplete upload");
            FileContentInspection.requireExpected(mime,prefix,prefixSize);
            out.flush();
            success=true;
        } finally {
            if(!success)Files.deleteIfExists(path);
        }
        return new Staged(key,bytes,HexFormat.of().formatHex(sha.digest()),mime,0);
    }
    @Override public String stageEncrypted(Expected expected,byte[] envelope) throws IOException {
        if(envelope==null||envelope.length!=FileContentCryptoPort.envelopeSize(expected))
            throw new IOException("REPORT_ENVELOPE_SIZE");
        bucket(expected.tenantId(),expected.fileId());
        String key=ReportStorageKey.of(false,true,expected);
        Path path=resolve(key);
        // CREATE_NEW must never replace an existing attempt.
        try(OutputStream out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            out.write(envelope);
        }
        // On an ambiguous or partial write, recovery owns the private orphan.
        return key;
    }
    @Override public String promoteEncrypted(Expected expected,String stagingKey) throws IOException {
        ReportStorageKey.require(stagingKey,false,true,expected);
        bucket(expected.tenantId(),expected.fileId());
        Path from=resolve(stagingKey);
        if(!Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS)
                || Files.size(from)!=FileContentCryptoPort.envelopeSize(expected))
            throw new IOException("REPORT_ENVELOPE_SIZE");
        String key=ReportStorageKey.of(false,false,expected);
        // Atomic no-replace hard-link within the same directory; no fallback to
        // replacement or plaintext. Unsupported filesystems fail closed.
        Files.createLink(resolve(key),from);
        Files.delete(from);
        return key;
    }
    @Override public InputStream openEncrypted(Expected expected,String key,boolean staged) throws IOException {
        ReportStorageKey.require(key,false,staged,expected);
        Path path=resolve(key);
        if(Files.isSymbolicLink(path.getParent())||Files.isSymbolicLink(path.getParent().getParent()))
            throw new IOException("Unsafe namespace");
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) {
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new java.io.FileNotFoundException("Encrypted file missing");
            throw new IOException("Unsafe encrypted file");
        }
        return Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS);
    }
    @Override public InputStream openStagedForScan(String stagingKey) throws IOException {
        if(stagingKey==null || !stagingKey.matches(
                "[0-9a-f-]{36}/[0-9a-f-]{36}/stage-[0-9a-f-]{36}"))
            throw new IOException("Scanner can only read private staging");
        Path p=resolve(stagingKey);
        if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Staging content missing");
        return Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS);
    }
    @Override public String promote(UUID tenant,UUID fileId,Staged staged) throws IOException {
        if(staged.cryptoVersion()!=0)throw new IOException("REPORT_STORAGE_REQUIRES_V1_PROMOTION");
        ReportStorageKey.rejectLegacyRead(staged.key());
        Path from=resolve(staged.key());
        if(!staged.key().startsWith(tenant+"/"+fileId+"/stage-"))
            throw new IOException("Wrong tenant/file staging key");
        String key=tenant+"/"+fileId+"/final-"+UUID.randomUUID();
        Path destination=resolve(key);
        Files.move(from,destination,StandardCopyOption.ATOMIC_MOVE);
        return key;
    }
    @Override public boolean exists(String key) throws IOException {
        Path path=resolve(key);
        return Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS);
    }
    @Override public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }
    @Override public boolean matches(String key,long expectedSize,String expectedHash) throws IOException {
        ReportStorageKey.rejectLegacyRead(key);
        if(expectedSize<0 || expectedHash==null || !expectedHash.matches("[0-9a-f]{64}"))
            return false;
        Path path=resolve(key);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                || Files.size(path)!=expectedSize)return false;
        MessageDigest digest;
        try {digest=MessageDigest.getInstance("SHA-256");}
        catch(Exception ex){throw new IOException("SHA-256 unavailable",ex);}
        try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] buf=new byte[64*1024];
            int n;
            while((n=in.read(buf))!=-1) {
                if(n>0)digest.update(buf,0,n);
            }
        }
        return java.security.MessageDigest.isEqual(
                java.util.HexFormat.of().formatHex(digest.digest()).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    /** Caller must check tenant/CRM owner policy and stored SHA-256 first. */
    @Override public void streamVerified(UUID tenant,UUID fileId,String key,OutputStream sink)
            throws IOException {
        ReportStorageKey.rejectLegacyRead(key);
        if(tenant==null || fileId==null || sink==null || key==null
                || !key.startsWith(tenant+"/"+fileId+"/final-"))
            throw new SecurityException("LOCAL binary read tenant/file scope denied");
        try(InputStream in=Files.newInputStream(resolve(key),LinkOption.NOFOLLOW_LINKS)) {
            in.transferTo(sink);
        }
    }
    @Override public java.util.List<String> listKeys(UUID tenant,UUID fileId) throws IOException {
        if(tenant==null || fileId==null)throw new IOException("Invalid file namespace");
        Path dir=root.resolve(tenant.toString()).resolve(fileId.toString()).normalize();
        if(!dir.startsWith(root) || Files.isSymbolicLink(dir)
                || Files.isSymbolicLink(dir.getParent()))
            throw new IOException("Unsafe storage namespace");
        if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return java.util.List.of();
        java.util.List<String> keys=new java.util.ArrayList<>();
        try(var stream=Files.newDirectoryStream(dir)) {
            for(Path p:stream) {
                if(keys.size()>=10000)throw new IOException("Recovery directory too large");
                String filename=p.getFileName().toString();
                if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)
                        && filename.matches("(stage|final)-(v1-)?[0-9a-f-]{36}"))
                    keys.add(tenant+"/"+fileId+"/"+filename);
            }
        }
        return keys;
    }

}
