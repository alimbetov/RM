package kz.rm.sales;

import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * QR-07/R2.5: independent, buyer-issued physical return proof.
 * Raw secret never persists; only a tenant, return and warehouse-bound SHA-256
 * digest is stored. A buyer can rotate the proof, invalidating old generations.
 */
@Service
public class SalesReturnReceptionProofService {
    public record Issued(String rawProof,Instant expiresAt,int generation) {}
    private record Source(UUID buyer,UUID warehouse,String state) {}
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final Duration TTL=Duration.ofMinutes(10); // UAT, not signed-off policy
    private final JdbcTemplate jdbc;

    public SalesReturnReceptionProofService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @Transactional
    public Issued issue(VerifiedActor buyer,UUID tenant,UUID returnId,String key){
        if(buyer==null||tenant==null||returnId==null)
            throw new SecurityException("RETURN_PROOF_BUYER_REQUIRED");
        if(!validKey(key))throw new IllegalArgumentException("RETURN_PROOF_KEY_INVALID");
        Source source=jdbc.query("""
                SELECT rr.buyer_user_id,h.warehouse_id,rr.state
                FROM crm.sales_return_request rr
                JOIN crm.sales_pickup_handover h
                  ON h.tenant_id=rr.tenant_id AND h.id=rr.source_handover_id
                WHERE rr.tenant_id=? AND rr.id=?
                FOR UPDATE OF rr
                """,rs->rs.next()?new Source(rs.getObject(1,UUID.class),
                        rs.getObject(2,UUID.class),rs.getString(3)):null,
                tenant,returnId);
        if(source==null||!source.buyer().equals(buyer.userId()))
            throw new SecurityException("RETURN_PROOF_NOT_OWNED");
        if(!"APPROVED".equals(source.state()))
            throw new IllegalStateException("RETURN_PROOF_REQUIRES_APPROVAL");
        Integer duplicates=jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_return_reception_proof
                WHERE tenant_id=? AND request_key=?
                """,Integer.class,tenant,key);
        if(duplicates!=null&&duplicates!=0)
            throw new IllegalStateException("RETURN_PROOF_RECOVERY_REQUIRED");
        Integer generation=jdbc.query("""
                SELECT generation FROM crm.sales_return_reception_proof
                WHERE tenant_id=? AND return_id=?
                """,rs->rs.next()?rs.getInt(1):0,tenant,returnId);
        int next=(generation==null?0:generation)+1;
        byte[] secret=new byte[32];
        RANDOM.nextBytes(secret);
        String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String digest=digest(tenant,source.warehouse(),returnId,raw);
        Instant now=Instant.now();
        if(next==1)jdbc.update("""
                INSERT INTO crm.sales_return_reception_proof
                 (tenant_id,return_id,warehouse_id,buyer_user_id,digest_sha256,
                  generation,request_key,state,issued_at,expires_at)
                VALUES (?,?,?,?,?,?,?,'ISSUED',?,?)
                """,tenant,returnId,source.warehouse(),buyer.userId(),digest,
                next,key,Timestamp.from(now),Timestamp.from(now.plus(TTL)));
        else jdbc.update("""
                UPDATE crm.sales_return_reception_proof
                SET digest_sha256=?,generation=?,request_key=?,state='ISSUED',
                    issued_at=?,expires_at=?,consumed_at=NULL
                WHERE tenant_id=? AND return_id=? AND state='ISSUED'
                """,digest,next,key,Timestamp.from(now),Timestamp.from(now.plus(TTL)),
                tenant,returnId);
        return new Issued(raw,now.plus(TTL),next);
    }

    /** Call only inside the same return-reception transaction after scope admission. */
    @Transactional(propagation=Propagation.MANDATORY)
    public String consume(UUID tenant,UUID warehouse,UUID returnId,String raw) {
        if(raw==null||!raw.matches("[A-Za-z0-9_-]{43}"))
            throw new SecurityException("RETURN_PROOF_INVALID");
        String hashed=digest(tenant,warehouse,returnId,raw);
        int count=jdbc.update("""
                UPDATE crm.sales_return_reception_proof
                SET state='CONSUMED',consumed_at=?
                WHERE tenant_id=? AND warehouse_id=? AND return_id=?
                  AND state='ISSUED' AND digest_sha256=? AND expires_at>?
                """,Timestamp.from(Instant.now()),tenant,warehouse,returnId,hashed,
                Timestamp.from(Instant.now()));
        if(count!=1)throw new SecurityException("RETURN_PROOF_UNAVAILABLE");
        return hashed;
    }

    public static String digest(UUID tenant,UUID warehouse,UUID returnId,String raw) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256")
                    .digest((tenant+":"+warehouse+":"+returnId+":"+raw)
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        }catch(java.security.NoSuchAlgorithmException ex){
            throw new IllegalStateException(ex);
        }
    }
    private static boolean validKey(String value){
        return value!=null&&value.matches("[A-Za-z0-9_-]{8,100}");
    }
}
