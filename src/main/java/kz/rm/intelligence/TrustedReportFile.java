package kz.rm.intelligence;

import kz.rm.iam.VerifiedActor;
import kz.rm.security.kms.FileContentCryptoPort.Format;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Owner-only renderer capability: no public constructor or raw-byte factory.
 * R02 XLSX pilot; READY describes dataset integrity, not publication. */
public final class TrustedReportFile {
    private final VerifiedActor actor;
    private final UUID instance;
    private final SalesReportMaterializer.Receipt receipt;
    private final ReportSourceManifest manifest;
    private final AuthorizedAnalyticalReadService reader;
    private final String requestDigest,sha;
    private final byte[] bytes;

    private TrustedReportFile(VerifiedActor actor,UUID instance,SalesReportMaterializer.Receipt receipt,
                              ReportSourceManifest manifest,AuthorizedAnalyticalReadService reader,String requestDigest){
        this.actor=Objects.requireNonNull(actor);this.instance=Objects.requireNonNull(instance);
        this.receipt=Objects.requireNonNull(receipt);this.manifest=Objects.requireNonNull(manifest);
        this.reader=Objects.requireNonNull(reader);this.requestDigest=Objects.requireNonNull(requestDigest);
        if(!receipt.tenant().equals(manifest.tenant())||!receipt.scopeDigest().equals(manifest.scopeDigest())
            ||!receipt.sourceDigest().equals(manifest.sha256())||!receipt.expected().equals(receipt.observed())
            ||manifest.accountingPolicy()==null)throw new SecurityException("REPORT_RENDER_SOURCE_MISMATCH");
        bytes=ReportTemplateRenderer.renderBooked("RM-R02",manifest);
        if(bytes.length<1||bytes.length>Format.XLSX.maxBytes())throw new IllegalStateException("REPORT_RENDER_SIZE");
        try{sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    static TrustedReportFile renderR02(VerifiedActor actor,UUID instance,SalesReportMaterializer.Receipt receipt,
                                       ReportSourceManifest manifest,AuthorizedAnalyticalReadService reader,String requestDigest){
        return new TrustedReportFile(actor,instance,receipt,manifest,reader,requestDigest);
    }
    public void reauthorize(VerifiedActor current){
        if(current==null||!actor.userId().equals(current.userId()))throw new SecurityException("REPORT_RENDER_ACTOR");
        reader.admitReportRecovery(current,tenant(),manifest.queries());
        reader.requireAccountingPolicy(tenant(),manifest.accountingPolicy(),true);
        current.userId();
    }
    public UUID tenant(){return receipt.tenant();}
    public UUID instance(){return instance;}
    public UUID dataset(){return receipt.dataset();}
    public LocalDate stagingDate(){return receipt.stagingDate();}
    public String scopeDigest(){return receipt.scopeDigest();}
    public String sourceDigest(){return receipt.sourceDigest();}
    public String requestDigest(){return requestDigest;}
    public String datasetDigest(){return receipt.observed().sha256();}
    public String reportCode(){return "RM-R02";}
    public String templateVersion(){return "1";}
    public String rendererVersion(){return "RM-R02-XLSX-P4.1A-v1";}
    public Format format(){return Format.XLSX;}
    public int size(){return bytes.length;}
    public String sha256(){return sha;}
    public InputStream open(){return new ByteArrayInputStream(bytes);}
}
