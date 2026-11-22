package kz.rm.files;

import kz.rm.iam.VerifiedActor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * QR-02A: a FILES-owned service-only media pipeline for seller PRODUCT cards.
 *
 * Source bytes are NEVER public. An admitted clean private JPEG/PNG is decoded
 * into a new pixel raster, re-encoded as metadata-free PNG, scanned again and
 * linked as an approved typed CATALOG_ITEM derivative. No DB transaction spans
 * storage, image decoding or malware scanning. The approval transaction fences
 * seller permissions, product ownership and attachment exclusivity again.
 */
@Service
public class CatalogPublicProductMedia {
    public record Image(String publicRef,String mimeType,int width,int height,int sortOrder) {}
    private record Raster(byte[] png,int width,int height) {}
    private static final int MAX_BYTES=20*1024*1024;
    private static final long MAX_PIXELS=4_000_000L;

    private final CatalogPublicMediaPolicy policy;
    private final FileStoragePort storage;
    private final FileMetadataService metadata;
    private final FileUploadCompletionService completion;

    CatalogPublicProductMedia(CatalogPublicMediaPolicy policy,FileStoragePort storage,
                              FileMetadataService metadata,FileUploadCompletionService completion){
        this.policy=policy;
        this.storage=storage;
        this.metadata=metadata;
        this.completion=completion;
    }

    /**
     * Source belongs to the verified seller uploader. requestKey is seller
     * tenant scoped. The caller's approval of the visible image is explicit.
     */
    public Image publishSanitized(VerifiedActor actor,UUID tenant,UUID itemId,
                                  UUID sourceFileId,int sortOrder,String requestKey,
                                  boolean sellerApproved) throws IOException {
        if(!sellerApproved)throw new SecurityException("Seller image approval required");
        var source=policy.authorizeSource(actor,tenant,itemId,sourceFileId,sortOrder,requestKey);
        if(source.existing()!=null)return source.existing();
        if(!storage.matches(source.storageKey(),source.size(),source.sha256()))
            throw new IOException("Source file integrity verification failed");
        var incoming=new BoundedBuffer(MAX_BYTES);
        storage.streamVerified(tenant,source.fileId(),source.storageKey(),incoming);
        if(incoming.size()!=source.size())
            throw new IOException("Source file length differs from authorized snapshot");

        Raster safe=sanitize(incoming.toByteArray(),source.mime());
        String derivativeKey=derivativeKey(tenant,actor.userId(),itemId,sourceFileId,
                                          sortOrder,requestKey);
        // Existing FILES upload/scan remains the only publisher of AVAILABLE bytes.
        var intent=metadata.begin(actor.userId(),tenant,"image/png",safe.png().length,
                derivativeKey);
        var ready=completion.acceptAndComplete(actor,tenant,intent.fileId(),
                new ByteArrayInputStream(safe.png()));
        if(!"AVAILABLE".equals(ready.state())||!"CLEAN".equals(ready.scanStatus()))
            throw new IOException("Derived public image was not virus-scanned clean");
        // Fresh IAM, PRODUCT and FILES checks after IO, all bindings atomically.
        return policy.approve(actor,tenant,itemId,sourceFileId,ready.fileId(),sortOrder,
                              requestKey,safe.width(),safe.height());
    }

    public List<Image> listPublished(String publishedOfferRef,int limit) {
        return policy.listPublished(publishedOfferRef,limit);
    }

    /** Called only by a future vetted transport; no URLs, keys or PII returned. */
    public long copyPublished(String publicMediaRef,OutputStream sink) throws IOException {
        if(sink==null)throw new IllegalArgumentException("Public binary sink required");
        var blob=policy.authorizePublishedBinary(publicMediaRef);
        if(!storage.matches(blob.storageKey(),blob.size(),blob.sha256()))
            throw new IOException("Public image integrity verification failed");
        // May finish if a concurrent revoke commits AFTER admission; new reads
        // must recheck publication. Bounded in-flight revocation is documented.
        storage.streamVerified(blob.tenant(),blob.fileId(),blob.storageKey(),sink);
        return blob.size();
    }

    public void revoke(VerifiedActor actor,UUID tenant,String publicMediaRef) {
        policy.revoke(actor,tenant,publicMediaRef);
    }

    private static Raster sanitize(byte[] original,String expectedMime) throws IOException {
        if(original==null||original.length<8||original.length>MAX_BYTES)
            throw new IOException("Image outside allowed source size");
        if(!"image/png".equals(expectedMime)&&!"image/jpeg".equals(expectedMime))
            throw new IOException("Only PNG and JPEG source images are supported");
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            if(stream==null)throw new IOException("Image input unavailable");
            Iterator<ImageReader> readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext())throw new IOException("Image decoder unavailable");
            ImageReader reader=readers.next();
            try {
                reader.setInput(stream,true,true); // no sidecar metadata required
                String actual=reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if(("image/png".equals(expectedMime)&&!"png".equals(actual))
                   ||("image/jpeg".equals(expectedMime)&&!"jpeg".equals(actual)
                         &&!"jpg".equals(actual)))
                    throw new IOException("Image MIME and decoded content mismatch");
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1||height<1||width>4096||height>4096
                        ||(long)width*height>MAX_PIXELS)
                    throw new IOException("Image pixel limit exceeded");
                BufferedImage decoded=reader.read(0);
                if(decoded==null||decoded.getWidth()!=width||decoded.getHeight()!=height)
                    throw new IOException("Image decoder failed to produce a valid raster");
                // A new raster has no EXIF/GPS/source metadata. Never publish
                // decoded source objects or their original binary representation.
                BufferedImage pixels=new BufferedImage(width,height,
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics=pixels.createGraphics();
                try {graphics.drawImage(decoded,0,0,null);}
                finally {graphics.dispose();}
                var sanitized=new BoundedBuffer(MAX_BYTES);
                if(!ImageIO.write(pixels,"png",sanitized))
                    throw new IOException("PNG encoder unavailable");
                if(sanitized.size()==0)throw new IOException("Empty sanitized image");
                return new Raster(sanitized.toByteArray(),width,height);
            } finally {
                reader.dispose();
            }
        }
    }

    private static String derivativeKey(UUID tenant,UUID actor,UUID item,UUID file,
                                        int slot,String key) {
        String canonical="RM/FILE-CATALOG-PNG-R1|"+tenant+"|"+actor+"|"+item
                +"|"+file+"|"+slot+"|"+key;
        try {
            return "QRIMG_"+HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                    canonical.getBytes(StandardCharsets.UTF_8))).substring(0,48);
        }catch(NoSuchAlgorithmException impossible){
            throw new IllegalStateException("SHA-256 unavailable",impossible);
        }
    }

    /** Guards both public import size and PNG encoder output size. */
    private static final class BoundedBuffer extends ByteArrayOutputStream {
        private final int maximum;
        BoundedBuffer(int maximum){this.maximum=maximum;}
        @Override public synchronized void write(int b) {
            if(size()>=maximum)throw new IllegalArgumentException("Image size limit");
            super.write(b);
        }
        @Override public synchronized void write(byte[] b,int off,int len) {
            if(len<0||size()>maximum-len)
                throw new IllegalArgumentException("Image size limit");
            super.write(b,off,len);
        }
    }
}
