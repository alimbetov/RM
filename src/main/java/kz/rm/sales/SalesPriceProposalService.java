package kz.rm.sales;

import jakarta.annotation.PostConstruct;
import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * QR-04A SALES-owned negotiation. No controllers or public-pricing changes.
 * One buyer, one seller, one published PRODUCT offer and fixed integer qty.
 * Persistent receipts return command-time outcomes, not the mutable projection.
 * UAT-only policy: 3 counters / 48h; business approval pending for production.
 */
@Service
public class SalesPriceProposalService {
    public enum Action { COUNTER, ACCEPT, REJECT, WITHDRAW }
    public enum State {
        AWAITING_SELLER, AWAITING_BUYER, AGREED, REJECTED, WITHDRAWN, EXPIRED
    }
    public record Result(UUID tenantId,UUID proposalId,UUID quoteId,
                         String state,long revision,int roundCount,
                         BigDecimal unitPrice,int quantity,String currency,
                         String nextActor,Instant expiresAt) {}
    public record Event(long revision,String eventType,String actorRole,
                        BigDecimal unitPrice,Instant occurredAt) {}
    public record Detail(Result current,List<Event> history) {}

    private record OfferLookup(UUID tenant,UUID offer) {}
    private record Offer(String state,UUID item,BigDecimal price,String currency) {}
    private record Proposal(UUID id,UUID tenant,UUID offer,UUID buyer,
                            int qty,BigDecimal price,String currency,
                            String state,long revision,int rounds,String next,
                            Instant expiresAt) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;
    private String userLockSql;
    public SalesPriceProposalService(JdbcTemplate jdbc,TenantAuthorization authorization){
        this.jdbc=jdbc;this.authorization=authorization;
    }
    @PostConstruct
    void configureUserLock() {
        String db=jdbc.execute((ConnectionCallback<String>) c ->
                c.getMetaData().getDatabaseProductName());
        userLockSql="SELECT status FROM public.iam_user_account WHERE id=? "+
                (db!=null && db.toLowerCase(java.util.Locale.ROOT).contains("postgresql")
                        ?"FOR SHARE":"FOR UPDATE");
    }

    @Transactional
    public Result submit(VerifiedActor buyer,String offerPublicRef,int quantity,
                         BigDecimal proposedUnitPrice,String currency,String requestKey) {
        UUID actor=buyerId(buyer);
        validateKey(requestKey);
        if(offerPublicRef==null||!offerPublicRef.matches("[A-Za-z0-9_-]{32}")
                ||quantity<1||quantity>1000)
            throw new IllegalArgumentException("PROPOSAL_INVALID_INPUT");
        BigDecimal proposed=price(proposedUnitPrice);
        if(currency==null||!currency.matches("[A-Z]{3}"))
            throw new IllegalArgumentException("PROPOSAL_CURRENCY_INVALID");
        OfferLookup locator=jdbc.query("""
                SELECT tenant_id,offer_id FROM crm.store_offer_qr_ref
                WHERE public_ref=?
                """,rs->rs.next()?new OfferLookup(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class)):null,offerPublicRef);
        if(locator==null)throw new IllegalArgumentException("QR_UNAVAILABLE");
        lockSeller(locator.tenant(),false);
        Offer offer=lockOffer(locator.tenant(),locator.offer());
        String fingerprint=fingerprint(actor,"SUBMIT",locator.offer(),
                offerPublicRef,0,quantity,proposed,currency);
        Result replay=receipt(locator.tenant(),requestKey,actor,"SUBMIT",fingerprint);
        if(replay!=null)return replay;
        published(locator.tenant(),locator.offer(),offer);
        Integer active=jdbc.queryForObject("""
                SELECT count(*) FROM crm.store_offer_qr_ref
                WHERE tenant_id=? AND offer_id=? AND public_ref=?
                 AND state='ACTIVE' AND active_offer_id=offer_id
                """,Integer.class,locator.tenant(),locator.offer(),offerPublicRef);
        if(active==null||active!=1)throw new IllegalArgumentException("QR_UNAVAILABLE");
        if(!offer.currency().equals(currency))
            throw new IllegalArgumentException("PROPOSAL_CURRENCY_MISMATCH");
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant expiry=now.plus(Duration.ofHours(48));
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.sales_price_proposal
                 (tenant_id,id,offer_id,catalog_item_id,buyer_user_id,quantity,
                  list_price_at_submit,current_unit_price,currency,state,
                  revision,next_actor,round_count,expires_at,created_at,
                  updated_at,row_version)
                VALUES (?,?,?,?,?,?,?,?,?,'AWAITING_SELLER',1,'SELLER',0,?,?,?,0)
                """,locator.tenant(),id,locator.offer(),offer.item(),actor,
                quantity,offer.price(),proposed,currency,Timestamp.from(expiry),
                Timestamp.from(now),Timestamp.from(now));
        event(locator.tenant(),id,1,"SUBMITTED","BUYER",actor,proposed,quantity,currency,now);
        Result result=new Result(locator.tenant(),id,null,"AWAITING_SELLER",
                1,0,proposed,quantity,currency,"SELLER",expiry);
        writeReceipt(actor,requestKey,"SUBMIT",fingerprint,result,now);
        return result;
    }

    @Transactional
    public Result buyerRespond(VerifiedActor buyer,UUID tenant,UUID proposalId,
                               long expectedRevision,Action action,
                               BigDecimal newPrice,String requestKey) {
        UUID actor=buyerId(buyer);
        return respond(actor,false,tenant,proposalId,expectedRevision,action,newPrice,requestKey);
    }

    @Transactional
    public Result sellerRespond(VerifiedActor seller,UUID tenant,UUID proposalId,
                                long expectedRevision,Action action,
                                BigDecimal newPrice,String requestKey) {
        if(seller==null||tenant==null)throw new SecurityException("Seller required");
        UUID actor=seller.userId();
        authorization.requireForCommand(actor,tenant,DomainPermission.SALES_PROPOSAL_RESPOND);
        return respond(actor,true,tenant,proposalId,expectedRevision,action,newPrice,requestKey);
    }

    private Result respond(UUID actor,boolean seller,UUID tenant,UUID proposalId,
                           long expectedRevision,Action action,
                           BigDecimal newPrice,String requestKey) {
        validateKey(requestKey);
        if(tenant==null||proposalId==null||action==null||expectedRevision<1)
            throw new IllegalArgumentException("PROPOSAL_INVALID_INPUT");
        if(action==Action.COUNTER)price(newPrice);
        else if(newPrice!=null)throw new IllegalArgumentException("Unexpected price for action");
        // Read untrusted ID just to determine the lock ordering. Always lock
        // workspace -> storefront -> offer -> proposal before any command effects.
        UUID offerId=jdbc.query("""
                SELECT offer_id FROM crm.sales_price_proposal
                WHERE tenant_id=? AND id=?
                """,rs->rs.next()?rs.getObject(1,UUID.class):null,tenant,proposalId);
        if(offerId==null)throw new SecurityException("PROPOSAL_NOT_ACCESSIBLE");
        lockSeller(tenant,seller);
        Offer offer=lockOffer(tenant,offerId);
        String actionCode=(seller?"SELLER_":"BUYER_")+action.name();
        String fingerprint=fingerprint(actor,actionCode,proposalId,
                null,expectedRevision,0,newPrice,null);
        Result replay=receipt(tenant,requestKey,actor,actionCode,fingerprint);
        if(replay!=null)return replay;
        Proposal p=lockProposal(tenant,proposalId);
        if(!seller && !p.buyer().equals(actor))
            throw new SecurityException("PROPOSAL_NOT_ACCESSIBLE");
        if(p.revision()!=expectedRevision)
            throw new IllegalStateException("PROPOSAL_VERSION_CONFLICT");
        if(Instant.now().compareTo(p.expiresAt())>=0)
            throw new IllegalStateException("PROPOSAL_EXPIRED");
        if(!p.state().startsWith("AWAITING_"))
            throw new IllegalStateException("PROPOSAL_TERMINAL");
        published(tenant,offerId,offer);
        String actorRole=seller?"SELLER":"BUYER";
        if(action==Action.WITHDRAW) {
            // The party who last proposed the current price can withdraw it.
            if(!p.next().equals(seller?"BUYER":"SELLER"))
                throw new SecurityException("PROPOSAL_TURN_REQUIRED");
        } else if(!p.next().equals(actorRole))
            throw new SecurityException("PROPOSAL_TURN_REQUIRED");
        int rounds=p.rounds(),qty=p.qty();
        BigDecimal effective=p.price();
        String state, next;
        UUID quoteId=null;
        if(action==Action.COUNTER) {
            if(rounds>=3)throw new IllegalStateException("PROPOSAL_ROUND_LIMIT");
            effective=price(newPrice);
            if(effective.compareTo(p.price())==0)
                throw new IllegalArgumentException("PROPOSAL_COUNTER_UNCHANGED");
            rounds++;
            next=seller?"BUYER":"SELLER";
            state=seller?"AWAITING_BUYER":"AWAITING_SELLER";
        } else {
            state=switch(action){
                case ACCEPT -> "AGREED";
                case REJECT -> "REJECTED";
                case WITHDRAW -> "WITHDRAWN";
                default -> throw new IllegalStateException("Unknown action");
            };
            next="NONE";
        }
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        long nextRevision=p.revision()+1;
        int changed=jdbc.update("""
                UPDATE crm.sales_price_proposal
                SET current_unit_price=?,state=?,revision=?,next_actor=?,
                    round_count=?,row_version=row_version+1,updated_at=?
                WHERE tenant_id=? AND id=? AND revision=?
                  AND state=? AND row_version=?
                """,effective,state,nextRevision,next,rounds,Timestamp.from(now),
                tenant,proposalId,p.revision(),p.state(),p.revision()-1);
        if(changed!=1)throw new IllegalStateException("PROPOSAL_VERSION_CONFLICT");
        if(action==Action.ACCEPT) {
            quoteId=UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO crm.sales_accepted_quote
                     (tenant_id,id,proposal_id,agreed_revision,offer_id,buyer_user_id,
                      quantity,agreed_unit_price,currency,state,expires_at,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,'AVAILABLE',?,?)
                    """,tenant,quoteId,proposalId,p.revision(),p.offer(),p.buyer(),
                    qty,effective,p.currency(),Timestamp.from(p.expiresAt()),
                    Timestamp.from(now));
        }
        event(tenant,proposalId,nextRevision,
                action==Action.WITHDRAW?"WITHDRAWN":action.name()+"ED",actorRole,actor,
                effective,qty,p.currency(),now);
        Result result=new Result(tenant,proposalId,quoteId,state,nextRevision,rounds,
                effective,qty,p.currency(),next,p.expiresAt());
        writeReceipt(actor,requestKey,actionCode,fingerprint,result,now);
        return result;
    }

    @Transactional
    public Detail buyerDetail(VerifiedActor buyer,UUID tenant,UUID proposalId) {
        UUID actor=buyerId(buyer);
        Proposal p=readProposal(tenant,proposalId);
        if(!p.buyer().equals(actor))throw new SecurityException("PROPOSAL_NOT_ACCESSIBLE");
        return project(p);
    }

    @Transactional(readOnly=true)
    public Detail sellerDetail(VerifiedActor seller,UUID tenant,UUID proposalId) {
        if(seller==null||tenant==null)
            throw new SecurityException("Seller required");
        authorization.require(seller.userId(),tenant,DomainPermission.SALES_PROPOSAL_RESPOND);
        return project(readProposal(tenant,proposalId));
    }

    /** Internal expiry worker command: no public API and no buyer-controlled
     * time. A late state does not authorize ACCEPT, even before materialization. */
    @Transactional
    int expireDue(UUID tenant,UUID proposalId) {
        Proposal p=lockProposal(tenant,proposalId);
        if(!p.state().startsWith("AWAITING_")||Instant.now().isBefore(p.expiresAt()))
            return 0;
        Instant now=Instant.now().truncatedTo(ChronoUnit.MICROS);
        int updated=jdbc.update("""
                UPDATE crm.sales_price_proposal SET state='EXPIRED',next_actor='NONE',
                  revision=revision+1,row_version=row_version+1,updated_at=?
                WHERE tenant_id=? AND id=? AND revision=?
                """,Timestamp.from(now),tenant,proposalId,p.revision());
        if(updated!=1)throw new IllegalStateException("PROPOSAL_VERSION_CONFLICT");
        event(tenant,proposalId,p.revision()+1,"EXPIRED","SYSTEM",null,
                p.price(),p.qty(),p.currency(),now);
        return 1;
    }

    private Detail project(Proposal p) {
        String state=p.state(),next=p.next();
        if(state.startsWith("AWAITING_")&&!Instant.now().isBefore(p.expiresAt())){
            state="EXPIRED";next="NONE";
        }
        List<UUID> quotes=jdbc.query("""
                SELECT id FROM crm.sales_accepted_quote
                WHERE tenant_id=? AND proposal_id=?
                """,(rs,n)->rs.getObject(1,UUID.class),p.tenant(),p.id());
        Result result=new Result(p.tenant(),p.id(),quotes.isEmpty()?null:quotes.getFirst(),
                state,p.revision(),p.rounds(),p.price(),p.qty(),p.currency(),
                next,p.expiresAt());
        List<Event> history=jdbc.query("""
                SELECT revision,event_type,actor_role,unit_price,occurred_at
                FROM crm.sales_price_proposal_event
                WHERE tenant_id=? AND proposal_id=?
                ORDER BY revision
                """,(rs,n)->new Event(rs.getLong(1),rs.getString(2),rs.getString(3),
                rs.getBigDecimal(4),rs.getTimestamp(5).toInstant()),
                p.tenant(),p.id());
        return new Detail(result,List.copyOf(history));
    }

    private void lockSeller(UUID tenant,boolean sellerAlreadyFenced) {
        // Seller authorization already holds a workspace SHARE fence through
        // TenantAuthorization. Do not upgrade that SHARE to UPDATE: two seller
        // responders doing so could deadlock. Buyer commands acquire the
        // workspace UPDATE lock and all commands lock storefront/offer next.
        String sql="SELECT status FROM public.iam_workspace "+
                "WHERE id=? AND kind='ORGANIZATION' "+
                (sellerAlreadyFenced?"":"FOR UPDATE");
        String status=jdbc.query(sql,rs->rs.next()?rs.getString(1):null,tenant);
        if(!"ACTIVE".equals(status))throw new IllegalStateException("SELLER_INACTIVE");
        String storefront=jdbc.query("""
                SELECT state FROM crm.store_front WHERE tenant_id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant);
        if(storefront==null)throw new IllegalStateException("SELLER_STOREFRONT_MISSING");
        // Publication is checked only for NEW commands; an exact receipt
        // replay remains readable after a later seller pause.
    }
    private Offer lockOffer(UUID tenant,UUID offerId) {
        Offer o=jdbc.query("""
                SELECT state,catalog_item_id,unit_price,currency
                FROM crm.store_offer WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?new Offer(rs.getString(1),rs.getObject(2,UUID.class),
                rs.getBigDecimal(3),rs.getString(4)):null,tenant,offerId);
        if(o==null)throw new SecurityException("PROPOSAL_NOT_ACCESSIBLE");
        return o;
    }
    private void published(UUID tenant,UUID offerId,Offer offer) {
        String storefront=jdbc.queryForObject("""
                SELECT state FROM crm.store_front WHERE tenant_id=?
                """,String.class,tenant);
        if(!"PUBLISHED".equals(storefront))
            throw new IllegalStateException("SELLER_STOREFRONT_PAUSED");
        if(!"PUBLISHED".equals(offer.state())||offer.item()==null)
            throw new IllegalStateException("OFFER_NOT_PUBLISHED");
        String item=jdbc.query("""
                SELECT kind || ':' || state FROM crm.cat_item
                WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant,offer.item());
        if(!"PRODUCT:PUBLISHED".equals(item))
            throw new IllegalStateException("PRODUCT_NOT_PUBLISHED");
    }
    private Proposal lockProposal(UUID tenant,UUID proposal) {
        return jdbc.query("""
                SELECT id,tenant_id,offer_id,buyer_user_id,quantity,
                       current_unit_price,currency,state,revision,
                       round_count,next_actor,expires_at
                FROM crm.sales_price_proposal WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->{
                    if(!rs.next())throw new SecurityException("PROPOSAL_NOT_ACCESSIBLE");
                    return mapProposal(rs);
                },tenant,proposal);
    }
    private Proposal readProposal(UUID tenant,UUID proposal) {
        if(tenant==null||proposal==null)
            throw new SecurityException("PROPOSAL_NOT_ACCESSIBLE");
        return jdbc.query("""
                SELECT id,tenant_id,offer_id,buyer_user_id,quantity,
                       current_unit_price,currency,state,revision,
                       round_count,next_actor,expires_at
                FROM crm.sales_price_proposal WHERE tenant_id=? AND id=?
                """,rs->{
                    if(!rs.next())throw new SecurityException("PROPOSAL_NOT_ACCESSIBLE");
                    return mapProposal(rs);
                },tenant,proposal);
    }
    private Proposal mapProposal(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Proposal(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
                rs.getInt(5),rs.getBigDecimal(6),rs.getString(7),rs.getString(8),
                rs.getLong(9),rs.getInt(10),rs.getString(11),rs.getTimestamp(12).toInstant());
    }

    private UUID buyerId(VerifiedActor actor){
        if(actor==null)throw new SecurityException("Verified buyer required");
        UUID user=actor.userId();
        String state=jdbc.query(userLockSql,rs->rs.next()?rs.getString(1):null,user);
        if(!"ACTIVE".equals(state))throw new SecurityException("Active buyer required");
        Integer verified=jdbc.queryForObject("""
                SELECT count(*) FROM public.iam_email_identity
                WHERE user_id=? AND lifecycle_state='CURRENT'
                  AND verified_at IS NOT NULL
                """,Integer.class,user);
        if(verified==null||verified!=1)
            throw new SecurityException("Verified email required");
        return user;
    }
    private static BigDecimal price(BigDecimal value) {
        if(value==null||value.signum()<=0||value.scale()>4
                ||value.precision()-value.scale()>15)
            throw new IllegalArgumentException("PROPOSAL_PRICE_INVALID");
        return value.setScale(4,RoundingMode.UNNECESSARY);
    }
    private static void validateKey(String key) {
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("PROPOSAL_REQUEST_KEY_INVALID");
    }

    private Result receipt(UUID tenant,String requestKey,UUID actor,
                           String action,String hash){
        List<Result> results=jdbc.query("""
                SELECT actor_user_id,action_code,request_sha256,proposal_id,
                       result_quote_id,result_state,result_revision,result_round_count,
                       result_unit_price,result_quantity,result_currency,
                       result_next_actor,result_expires_at
                FROM crm.sales_proposal_command_receipt
                WHERE tenant_id=? AND request_key=?
                """,(rs,n)->{
                    if(!actor.equals(rs.getObject(1,UUID.class))
                            ||!action.equals(rs.getString(2))
                            ||!hash.equals(rs.getString(3)))
                        throw new IllegalStateException("PROPOSAL_IDEMPOTENCY_CONFLICT");
                    return new Result(tenant,rs.getObject(4,UUID.class),
                            rs.getObject(5,UUID.class),rs.getString(6),rs.getLong(7),
                            rs.getInt(8),rs.getBigDecimal(9),rs.getInt(10),
                            rs.getString(11),rs.getString(12),
                            rs.getTimestamp(13).toInstant());
                },tenant,requestKey);
        return results.isEmpty()?null:results.getFirst();
    }
    private void writeReceipt(UUID actor,String key,String action,
                              String hash,Result r,Instant now) {
        jdbc.update("""
                INSERT INTO crm.sales_proposal_command_receipt
                 (tenant_id,request_key,actor_user_id,action_code,request_sha256,
                  proposal_id,result_revision,result_state,result_next_actor,
                  result_round_count,result_unit_price,result_quantity,
                  result_currency,result_expires_at,result_quote_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,r.tenantId(),key,actor,action,hash,r.proposalId(),
                r.revision(),r.state(),r.nextActor(),r.roundCount(),r.unitPrice(),
                r.quantity(),r.currency(),Timestamp.from(r.expiresAt()),
                r.quoteId(),Timestamp.from(now));
    }
    private void event(UUID tenant,UUID proposal,long revision,String event,
                       String role,UUID actor,BigDecimal amount,int qty,
                       String currency,Instant now){
        jdbc.update("""
                INSERT INTO crm.sales_price_proposal_event
                 (tenant_id,id,proposal_id,revision,event_type,actor_role,
                  actor_user_id,unit_price,quantity,currency,occurred_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),proposal,revision,event,role,actor,
                amount,qty,currency,Timestamp.from(now));
    }
    private static String fingerprint(UUID actor,String action,UUID subject,
                                      String ref,long rev,int qty,BigDecimal price,
                                      String currency){
        String value="RM/QR-04A/V1|"+actor+"|"+action+"|"+subject+"|"+ref+"|"+
                rev+"|"+qty+"|"+(price==null?"NULL":price.stripTrailingZeros()
                .toPlainString())+"|"+currency;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception noSha){
            throw new IllegalStateException("SHA-256 required",noSha);
        }
    }
}
