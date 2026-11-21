package kz.rm.files;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** Private S3-compatible RustFS storage, configured only with an explicit endpoint. */
@Component
@ConditionalOnProperty(name="rm.files.rustfs.endpoint")
class RustFsFileStorage implements FileStoragePort {
    private static final Pattern KEY=Pattern.compile(
        "rustfs/[0-9a-f-]{36}/[0-9a-f-]{36}/(stage|final)-[0-9a-f-]{36}");
    private final S3Client s3;
    private final String bucket;

    @Autowired
    RustFsFileStorage(@Value("${rm.files.rustfs.endpoint}") String endpoint,
                      @Value("${rm.files.rustfs.region:us-east-1}") String region,
                      @Value("${rm.files.rustfs.bucket:rm-files}") String bucket,
                      @Value("${rm.files.rustfs.access-key:}") String access,
                      @Value("${rm.files.rustfs.secret-key:}") String secret) {
        this(client(endpoint,region,access,secret),bucket);
    }
    RustFsFileStorage(S3Client client,String bucket) {
        if(client==null || bucket==null || !bucket.matches("[a-z0-9][a-z0-9.-]{2,62}")
                || bucket.contains("..") || bucket.endsWith("."))
            throw new IllegalArgumentException("Invalid RustFS bucket or S3 client");
        this.s3=client;this.bucket=bucket;
    }
    private static S3Client client(String endpoint,String region,String access,String secret) {
        URI uri;
        try {uri=URI.create(endpoint);}catch(RuntimeException e){throw new IllegalArgumentException("Invalid endpoint",e);}
        if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || (uri.getPath()!=null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath())))
            throw new IllegalArgumentException("RustFS requires an HTTP(S) S3 origin");
        if(region==null||region.isBlank()||access==null||access.isBlank()
                || secret==null||secret.isBlank())
            throw new IllegalArgumentException("Explicit RustFS credentials and region required");
        return S3Client.builder().endpointOverride(uri).region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(access,secret))).forcePathStyle(true).build();
    }
    static boolean isRustKey(String key){return key!=null && KEY.matcher(key).matches();}
    private String key(String tagged) throws IOException {
        if(!isRustKey(tagged))throw new IOException("Invalid RustFS storage key");
        return tagged.substring(7);
    }
    private String next(UUID tenant,UUID file,String phase) throws IOException {
        if(tenant==null||file==null)throw new IOException("Tenant/file required");
        return "rustfs/"+tenant+"/"+file+"/"+phase+"-"+UUID.randomUUID();
    }
    private static MessageDigest sha() throws IOException {
        try {return MessageDigest.getInstance("SHA-256");}
        catch(Exception e){throw new IOException("SHA-256 unavailable",e);}
    }

    /** Spools at most 25 MiB to a private temporary file for retry-safe S3 upload. */
    @Override public Staged stage(UUID tenant,UUID file,InputStream data,long declared,String mime)
            throws IOException {
        if(data==null||declared<1||declared>25L*1024*1024)
            throw new IOException("Invalid RustFS declared length");
        String ref=next(tenant,file,"stage");
        Path temp=Files.createTempFile("rm-rustfs-stage-", ".tmp");
        try {
            try {Files.setPosixFilePermissions(temp,Set.of(
                PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));}
            catch(UnsupportedOperationException ignored){}
            MessageDigest digest=sha();
            byte[] signature=new byte[16];int prefix=0;
            long count=0;byte[] buffer=new byte[64*1024];
            try(OutputStream out=Files.newOutputStream(temp,StandardOpenOption.TRUNCATE_EXISTING)) {
                int n;
                while((n=data.read(buffer))!=-1) {
                    if(n==0)continue;
                    count+=n;
                    if(count>declared)throw new IOException("Upload exceeds declared length");
                    if(prefix<signature.length) {
                        int len=Math.min(n,signature.length-prefix);
                        System.arraycopy(buffer,0,signature,prefix,len);prefix+=len;
                    }
                    digest.update(buffer,0,n);out.write(buffer,0,n);
                }
            }
            if(count!=declared)throw new IOException("Truncated RustFS upload");
            FileContentInspection.requireExpected(mime,signature,prefix);
            try {
                s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key(ref))
                    .contentType("application/octet-stream").build(),RequestBody.fromFile(temp));
                var result=s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key(ref)).build());
                if(result.contentLength()==null || result.contentLength()!=declared)
                    throw new IOException("RustFS staged object size mismatch");
            }catch(RuntimeException|IOException e) {
                try {delete(ref);}catch(IOException ignored){}
                if(e instanceof IOException io)throw io;
                throw new IOException("RustFS staging error",e);
            }
            return new Staged(ref,count,HexFormat.of().formatHex(digest.digest()),mime,0);
        } finally {Files.deleteIfExists(temp);}
    }
    @Override public String promote(UUID tenant,UUID file,Staged staged) throws IOException {
        if(tenant==null||file==null||staged==null||staged.cryptoVersion()!=0
            || staged.key()==null
            || !staged.key().startsWith("rustfs/"+tenant+"/"+file+"/stage-"))
            throw new IOException("RustFS staging owner mismatch");
        String dest=next(tenant,file,"final");
        try {
            s3.copyObject(CopyObjectRequest.builder().copySource(bucket+"/"+key(staged.key()))
                .bucket(bucket).key(key(dest)).build());
            var head=s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(dest)).build());
            if(head.contentLength()==null||head.contentLength()!=staged.sizeBytes())
                throw new IOException("RustFS final size mismatch");
            delete(staged.key());
            return dest;
        }catch(RuntimeException|IOException e) {
            try {delete(dest);}catch(IOException ignored){}
            if(e instanceof IOException io)throw io;
            throw new IOException("RustFS promote failed",e);
        }
    }
    private InputStream get(String ref) throws IOException {
        try {return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key(ref)).build());}
        catch(S3Exception e) {
            if(e.statusCode()==404)throw new FileNotFoundException("RustFS object missing");
            throw new IOException("RustFS read unavailable",e);
        }
        catch(RuntimeException e){throw new IOException("RustFS read unavailable",e);}
    }
    @Override public InputStream openStagedForScan(String ref) throws IOException {
        if(!isRustKey(ref)||!ref.contains("/stage-"))
            throw new IOException("Scanner access denied");
        return get(ref);
    }
    @Override public boolean exists(String ref) throws IOException {
        try {s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(ref)).build());
            return true;
        }catch(S3Exception e) {
            if(e.statusCode()==404)return false;
            throw new IOException("RustFS HEAD failed",e);
        }catch(RuntimeException e){throw new IOException("RustFS HEAD failed",e);}
    }
    @Override public boolean matches(String ref,long length,String sha256) throws IOException {
        if(length<0||sha256==null||!sha256.matches("[0-9a-f]{64}"))return false;
        MessageDigest digest=sha();long count=0;
        try(InputStream in=get(ref)) {
            byte[] buf=new byte[64*1024];int n;
            while((n=in.read(buf))!=-1) {
                if(n>0) {
                    count+=n;
                    if(count>length)return false;
                    digest.update(buf,0,n);
                }
            }
        }catch(FileNotFoundException e){return false;}
        return count==length && MessageDigest.isEqual(digest.digest(),HexFormat.of().parseHex(sha256));
    }
    @Override public void streamVerified(UUID tenant,UUID file,String ref,OutputStream sink)
            throws IOException {
        if(tenant==null||file==null||sink==null||ref==null
                || !ref.startsWith("rustfs/"+tenant+"/"+file+"/final-"))
            throw new SecurityException("RustFS read scope denied");
        try(InputStream in=get(ref)){in.transferTo(sink);}
    }
    @Override public void delete(String ref) throws IOException {
        try {s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(ref)).build());}
        catch(RuntimeException e){throw new IOException("RustFS delete failed",e);}
    }
    @Override public List<String> listKeys(UUID tenant,UUID file) throws IOException {
        if(tenant==null||file==null)throw new IOException("Tenant/file required");
        String prefix=tenant+"/"+file+"/";String token=null;
        List<String> result=new ArrayList<>();
        try {
            do {
                var request=ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).maxKeys(1000);
                if(token!=null)request.continuationToken(token);
                var page=s3.listObjectsV2(request.build());
                for(var object:page.contents()) {
                    String full="rustfs/"+object.key();
                    if(isRustKey(full)) {
                        if(result.size()>=10000)throw new IOException("RustFS recovery list limit");
                        result.add(full);
                    }
                }
                token=Boolean.TRUE.equals(page.isTruncated())?page.nextContinuationToken():null;
                if(token!=null && token.isBlank())
                    throw new IOException("Invalid RustFS continuation");
            }while(token!=null);
        }catch(RuntimeException e){throw new IOException("RustFS list failed",e);}
        return result;
    }
}
