package kz.rm.files;

import kz.rm.security.kms.TenantKeyService;
import kz.rm.security.kms.KeyWrappingService;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Independent R1.1 chunk envelope: AES-256-GCM with per-file nonce prefix,
 * per-chunk unique counter and authenticated terminal marker (truncation denial).
 * Does not turn on CRM binary downloads without a secure storage integration.
 */
@Service
class AesGcmFileContentCrypto implements FileContentCryptoPort {
    private static final int MAGIC=0x524D4643; // RMFC
    private static final int FORMAT=1;
    private static final int CHUNK=64*1024;
    private final TenantKeyService keys;
    private final KeyWrappingService wrapping;
    private final SecureRandom random=new SecureRandom();

    AesGcmFileContentCrypto(TenantKeyService keys,KeyWrappingService wrapping){
        this.keys=keys;this.wrapping=wrapping;
    }
    @Override public void encrypt(UUID tenant,UUID file,InputStream in,OutputStream destination)
            throws IOException {
        if(tenant==null || file==null || in==null || destination==null)
            throw new IllegalArgumentException("Invalid file encryption arguments");
        var slot=keys.active(tenant);
        long generation=slot.generation();
        SecretKey key=wrapping.unwrap(tenant,generation,slot.wrappedKey());
        byte[] prefix=new byte[8];random.nextBytes(prefix);
        var out=new DataOutputStream(destination);
        out.writeInt(MAGIC);out.writeInt(FORMAT);out.writeLong(generation);out.write(prefix);
        int index=0;
        byte[] buf=new byte[CHUNK];
        while(true){
            int n=in.read(buf);
            if(n<0)break;
            if(n==0)continue;
            if(index==Integer.MAX_VALUE)throw new IOException("File too large");
            byte[] cipher=crypt(Cipher.ENCRYPT_MODE,key,tenant,file,generation,prefix,index,
                    false,buf,n);
            out.writeInt(cipher.length);out.write(cipher);
            index++;
        }
        // Authenticated EOF: an attacker cannot truncate or concatenate chunk streams.
        byte[] tag=crypt(Cipher.ENCRYPT_MODE,key,tenant,file,generation,prefix,index,
                true,new byte[0],0);
        out.writeInt(-1);out.write(tag);
        out.flush();
    }
    @Override public void decrypt(UUID tenant,UUID file,InputStream encrypted,OutputStream plain)
            throws IOException {
        if(tenant==null||file==null||encrypted==null||plain==null)
            throw new IllegalArgumentException("Invalid file decryption arguments");
        var in=new DataInputStream(encrypted);
        if(in.readInt()!=MAGIC || in.readInt()!=FORMAT)
            throw new IOException("Unsupported file crypto envelope");
        long generation=in.readLong();
        if(generation<1)throw new IOException("Invalid file key generation");
        byte[] prefix=new byte[8];in.readFully(prefix);
        var slot=keys.forDecrypt(tenant,generation);
        SecretKey key=wrapping.unwrap(tenant,generation,slot.wrappedKey());
        int index=0;
        while(true){
            int size=in.readInt();
            if(size==-1){
                byte[] tag=new byte[16];in.readFully(tag);
                crypt(Cipher.DECRYPT_MODE,key,tenant,file,generation,prefix,index,true,tag,tag.length);
                if(in.read()!=-1)throw new IOException("Trailing ciphertext denied");
                plain.flush();return;
            }
            if(size<17||size>CHUNK+16 || index==Integer.MAX_VALUE)
                throw new IOException("Invalid encrypted file frame");
            byte[] frame=new byte[size];in.readFully(frame);
            byte[] clear=crypt(Cipher.DECRYPT_MODE,key,tenant,file,generation,prefix,index,
                    false,frame,frame.length);
            plain.write(clear);
            index++;
        }
    }
    private static byte[] crypt(int mode,SecretKey key,UUID tenant,UUID file,long gen,
                                byte[] prefix,int index,boolean terminal,byte[] bytes,int length)
            throws IOException {
        try {
            byte[] nonce=ByteBuffer.allocate(12).put(prefix).putInt(index).array();
            byte[] aad=ByteBuffer.allocate(4+16+16+8+4+1)
                    .putInt(FORMAT)
                    .putLong(tenant.getMostSignificantBits()).putLong(tenant.getLeastSignificantBits())
                    .putLong(file.getMostSignificantBits()).putLong(file.getLeastSignificantBits())
                    .putLong(gen).putInt(index).put((byte)(terminal?1:0)).array();
            Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode,key,new GCMParameterSpec(128,nonce));
            c.updateAAD(aad);
            return c.doFinal(bytes,0,length);
        } catch(GeneralSecurityException ex){
            throw new IOException("File authentication or encryption failed",ex);
        }
    }
}
