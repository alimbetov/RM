package kz.rm.crm;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * CRM-R2.2: a tenant-owned CRM customer is not an IAM identity.
 * This service records a proposed mapping, waits for explicit buyer approval,
 * and reads seller-owned SALES history only after confirmation.
 * It does not copy PII, change the SALES order owner or mint a principal.
 */
@Service
public class CrmBuyerLinkService {
    public enum State { PENDING, CONFIRMED, DISPUTED, REVOKED }
    public record Link(UUID tenantId, UUID id, UUID customerId, UUID buyerUserId,
                       State state, long rowVersion) {}
    public record BuyerOrder(UUID orderId, String status, Instant createdAt) {}
    public record LinkEvent(UUID id, String action, UUID actorUserId, Instant occurredAt) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization authorization;

    public CrmBuyerLinkService(JdbcTemplate jdbc, TenantAuthorization authorization) {
        this.jdbc=jdbc;
        this.authorization=authorization;
    }

    /**
     * Seller proposes the link. Same intent + same request key is idempotent;
     * any other proposal for that customer or buyer is an explicit conflict.
     */
    @Transactional
    public Link propose(VerifiedActor seller, UUID tenant, UUID customer,
                        UUID buyer, String requestKey) {
        if(seller==null || tenant==null) throw new SecurityException("Seller and tenant required");
        UUID proposer=seller.userId();
        authorization.requireForCommand(proposer,tenant,DomainPermission.CRM_WRITE);
        if(customer==null || buyer==null || requestKey==null
                || !requestKey.matches("[A-Za-z0-9_-]{8,100}"))
            throw new IllegalArgumentException("Invalid buyer link proposal");
        String customerStatus=jdbc.query("""
                SELECT status FROM crm.customer WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?rs.getString(1):null,tenant,customer);
        if(!"ACTIVE".equals(customerStatus))
            throw new IllegalArgumentException("Active CRM customer required");
        String buyerStatus=jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=?
                """,rs->rs.next()?rs.getString(1):null,buyer);
        if(!"ACTIVE".equals(buyerStatus)) throw new SecurityException("Buyer account inactive");
        List<Link> byKey=jdbc.query("""
                SELECT id,customer_id,buyer_user_id,status,row_version
                FROM crm.customer_buyer_link WHERE tenant_id=? AND request_key=?
                """,(rs,i)->link(tenant,rs),tenant,requestKey);
        if(!byKey.isEmpty()) {
            Link old=byKey.getFirst();
            if(old.customerId().equals(customer) && old.buyerUserId().equals(buyer))
                return old;
            throw new IllegalStateException("Request key used for another intent");
        }
        Integer existing=jdbc.queryForObject("""
                SELECT count(*) FROM crm.customer_buyer_link
                WHERE tenant_id=? AND status IN ('PENDING','CONFIRMED','DISPUTED')
                  AND (customer_id=? OR buyer_user_id=?)
                """,Integer.class,tenant,customer,buyer);
        if(existing!=null && existing>0)
            throw new IllegalStateException("Customer or buyer already linked in tenant");
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.customer_buyer_link
                (tenant_id,id,customer_id,buyer_user_id,proposed_by,status,request_key,created_at,row_version)
                VALUES (?,?,?,?,?,'PENDING',?,?,0)
                """,tenant,id,customer,buyer,proposer,requestKey,Timestamp.from(Instant.now()));
        event(tenant,id,proposer,"PROPOSED");
        return new Link(tenant,id,customer,buyer,State.PENDING,0);
    }

    /**
     * Only the verified BUYER can approve. Require an existing seller-owned
     * order explicitly assigned to this buyer; pending email alone is not proof.
     * No seller membership is assumed or required for the buyer.
     */
    @Transactional
    public Link confirm(VerifiedActor buyer, UUID tenant, UUID linkId, long expectedVersion) {
        if(buyer==null || tenant==null || linkId==null) throw new SecurityException("Buyer link required");
        if(expectedVersion<0) throw new IllegalArgumentException("Invalid version");
        UUID buyerId=buyer.userId();
        Link current=jdbc.query("""
                SELECT id,customer_id,buyer_user_id,status,row_version
                FROM crm.customer_buyer_link WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?link(tenant,rs):null,tenant,linkId);
        if(current==null || !buyerId.equals(current.buyerUserId()))
            throw new SecurityException("Only linked buyer can confirm");
        String accountStatus=jdbc.query("""
                SELECT status FROM public.iam_user_account WHERE id=? FOR SHARE
                """,rs->rs.next()?rs.getString(1):null,buyerId);
        if(!"ACTIVE".equals(accountStatus)) throw new SecurityException("Inactive buyer");
        String customerStatus=jdbc.query("""
                SELECT status FROM crm.customer WHERE tenant_id=? AND id=?
                """,rs->rs.next()?rs.getString(1):null,tenant,current.customerId());
        if(!"ACTIVE".equals(customerStatus)) throw new IllegalStateException("Archived customer");
        if(current.rowVersion()!=expectedVersion)
            throw new IllegalStateException("Stale buyer link revision");
        if(current.state()==State.CONFIRMED) return current;
        if(current.state()!=State.PENDING)
            throw new IllegalStateException("Link is no longer awaiting confirmation");
        Integer orderCount=jdbc.queryForObject("""
                SELECT count(*) FROM crm.sales_order
                WHERE tenant_id=? AND buyer_user_id=?
                """,Integer.class,tenant,buyerId);
        if(orderCount==null || orderCount==0)
            throw new SecurityException("Confirmed buyer order is required");
        int updated=jdbc.update("""
                UPDATE crm.customer_buyer_link
                SET status='CONFIRMED',confirmed_at=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND status='PENDING' AND row_version=?
                """,Timestamp.from(Instant.now()),tenant,linkId,expectedVersion);
        if(updated!=1) throw new IllegalStateException("Concurrent confirmation");
        event(tenant,linkId,buyerId,"CONFIRMED");
        return new Link(tenant,linkId,current.customerId(),buyerId,State.CONFIRMED,expectedVersion+1);
    }

    /** Seller CRM and SALES read permissions are BOTH mandatory. */
    @Transactional
    public List<BuyerOrder> orderHistory(VerifiedActor seller, UUID tenant,
                                         UUID customer, int limit) {
        if(seller==null || tenant==null) throw new SecurityException("Seller and tenant required");
        UUID user=seller.userId();
        authorization.requireForCommand(user,tenant,DomainPermission.CRM_READ);
        authorization.requireForCommand(user,tenant,DomainPermission.SALES_ORDER_READ);
        if(customer==null || limit<1 || limit>100)
            throw new IllegalArgumentException("Customer and limit 1..100 required");
        return jdbc.query("""
                SELECT o.id,o.status,o.created_at FROM crm.sales_order o
                JOIN crm.customer_buyer_link l
                  ON l.tenant_id=o.tenant_id AND l.buyer_user_id=o.buyer_user_id
                JOIN crm.customer c
                  ON c.tenant_id=l.tenant_id AND c.id=l.customer_id
                WHERE l.tenant_id=? AND l.customer_id=? AND l.status='CONFIRMED'
                  AND c.status='ACTIVE'
                ORDER BY o.created_at DESC,o.id DESC LIMIT ?
                """,(rs,i)->new BuyerOrder(rs.getObject(1,UUID.class),
                rs.getString(2),rs.getTimestamp(3).toInstant()),tenant,customer,limit);
    }

    @Transactional
    public Link linkForCustomer(VerifiedActor seller, UUID tenant, UUID customer) {
        if(seller==null || tenant==null) throw new SecurityException("Seller and tenant required");
        authorization.requireForCommand(seller.userId(),tenant,DomainPermission.CRM_READ);
        if(customer==null) throw new IllegalArgumentException("Customer required");
        return jdbc.query("""
                SELECT id,customer_id,buyer_user_id,status,row_version
                FROM crm.customer_buyer_link
                WHERE tenant_id=? AND customer_id=?
                  AND status IN ('PENDING','CONFIRMED','DISPUTED')
                """,rs->rs.next()?link(tenant,rs):null,tenant,customer);
    }


    /**
     * Buyer withdraws consent. Release of the active uniqueness slot is
     * atomic with the event. A revoked link cannot be reconfirmed.
     */
    @Transactional
    public Link revokeByBuyer(VerifiedActor buyer, UUID tenant, UUID linkId, long expectedVersion) {
        if(buyer==null || tenant==null || linkId==null) throw new SecurityException("Buyer required");
        UUID buyerId=buyer.userId();
        Link current=lockLink(tenant,linkId);
        if(current==null || !buyerId.equals(current.buyerUserId()))
            throw new SecurityException("Only the linked buyer can revoke");
        return transition(current,expectedVersion,buyerId,
                new State[]{State.PENDING,State.CONFIRMED,State.DISPUTED},
                State.REVOKED,"REVOKED");
    }

    /** Seller can close their own CRM mapping, but cannot transfer buyer consent. */
    @Transactional
    public Link releaseBySeller(VerifiedActor seller, UUID tenant, UUID linkId, long expectedVersion) {
        UUID sellerId=requireSeller(seller,tenant,DomainPermission.CRM_WRITE);
        Link current=lockLink(tenant,linkId);
        if(current==null) throw new IllegalArgumentException("No tenant link");
        return transition(current,expectedVersion,sellerId,
                new State[]{State.PENDING,State.CONFIRMED},
                State.REVOKED,"REVOKED");
    }

    /** Only the linked buyer may dispute an already confirmed identity match. */
    @Transactional
    public Link disputeByBuyer(VerifiedActor buyer, UUID tenant, UUID linkId, long expectedVersion) {
        if(buyer==null || tenant==null || linkId==null) throw new SecurityException("Buyer required");
        UUID buyerId=buyer.userId();
        Link current=lockLink(tenant,linkId);
        if(current==null || !buyerId.equals(current.buyerUserId()))
            throw new SecurityException("Only linked buyer can dispute");
        return transition(current,expectedVersion,buyerId,
                new State[]{State.CONFIRMED},State.DISPUTED,"DISPUTED");
    }

    /**
     * Dispute resolution is fail-closed: seller closes DISPUTED to REVOKED,
     * never silently restores CONFIRMED. New consent requires a new proposal.
     */
    @Transactional
    public Link closeDispute(VerifiedActor seller, UUID tenant, UUID linkId, long expectedVersion) {
        UUID sellerId=requireSeller(seller,tenant,DomainPermission.CRM_WRITE);
        Link current=lockLink(tenant,linkId);
        if(current==null) throw new IllegalArgumentException("No tenant link");
        return transition(current,expectedVersion,sellerId,
                new State[]{State.DISPUTED},State.REVOKED,"DISPUTE_CLOSED");
    }

    /** Historical events are append-only, including for revoked link generations. */
    @Transactional
    public List<LinkEvent> linkHistory(VerifiedActor seller, UUID tenant, UUID customer) {
        requireSeller(seller,tenant,DomainPermission.CRM_READ);
        if(customer==null) throw new IllegalArgumentException("Customer required");
        return jdbc.query("""
                SELECT e.id,e.action_code,e.actor_user_id,e.occurred_at
                FROM crm.customer_buyer_link_event e
                JOIN crm.customer_buyer_link l
                  ON l.tenant_id=e.tenant_id AND l.id=e.link_id
                WHERE l.tenant_id=? AND l.customer_id=?
                ORDER BY e.occurred_at,e.id
                """,(rs,n)->new LinkEvent(rs.getObject(1,UUID.class),
                rs.getString(2),rs.getObject(3,UUID.class),
                rs.getTimestamp(4).toInstant()),tenant,customer);
    }

    private UUID requireSeller(VerifiedActor actor,UUID tenant,DomainPermission permission) {
        if(actor==null || tenant==null) throw new SecurityException("Seller and tenant required");
        UUID id=actor.userId();
        authorization.requireForCommand(id,tenant,permission);
        return id;
    }

    private Link lockLink(UUID tenant,UUID linkId) {
        return jdbc.query("""
                SELECT id,customer_id,buyer_user_id,status,row_version
                FROM crm.customer_buyer_link WHERE tenant_id=? AND id=? FOR UPDATE
                """,rs->rs.next()?link(tenant,rs):null,tenant,linkId);
    }

    private Link transition(Link current,long expectedVersion,UUID actor,
                            State[] allowed,State next,String action) {
        if(expectedVersion<0) throw new IllegalArgumentException("Invalid revision");
        if(current.rowVersion()!=expectedVersion)
            throw new IllegalStateException("Stale buyer link revision");
        boolean permitted=false;
        for(State state:allowed) if(current.state()==state) permitted=true;
        if(!permitted) throw new IllegalStateException("Invalid buyer link transition");
        int updated=jdbc.update("""
                UPDATE crm.customer_buyer_link
                SET status=?,row_version=row_version+1
                WHERE tenant_id=? AND id=? AND row_version=? AND status=?
                """,next.name(),current.tenantId(),current.id(),
                expectedVersion,current.state().name());
        if(updated!=1) throw new IllegalStateException("Concurrent buyer link transition");
        event(current.tenantId(),current.id(),actor,action);
        return new Link(current.tenantId(),current.id(),current.customerId(),
                current.buyerUserId(),next,expectedVersion+1);
    }

    private Link link(UUID tenant, java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Link(tenant,rs.getObject("id",UUID.class),
                rs.getObject("customer_id",UUID.class),
                rs.getObject("buyer_user_id",UUID.class),
                State.valueOf(rs.getString("status")),rs.getLong("row_version"));
    }

    private void event(UUID tenant,UUID link,UUID actor,String action) {
        jdbc.update("""
                INSERT INTO crm.customer_buyer_link_event
                (tenant_id,id,link_id,actor_user_id,action_code,occurred_at)
                VALUES (?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),link,actor,action,Timestamp.from(Instant.now()));
    }
}
