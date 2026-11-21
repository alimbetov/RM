package kz.rm.files;

import org.springframework.beans.factory.annotation.Value;
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
        if(key==null || !key.matches("[0-9a-f-]{36}/[0-9a-f-]{36}/(stage|final)-[0-9a-f-]{36}"))
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
        return new Staged(key,bytes,HexFormat.of().formatHex(sha.digest()),mime);
    }
    @Override public String promote(UUID tenant,UUID fileId,Staged staged) throws IOException {
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
}
