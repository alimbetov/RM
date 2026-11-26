package kz.rm.catalog;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Append-only nomenclature hierarchy. A revision points to exact parent revision. */
@Service
public class CatalogGroupService {
    public record Node(UUID id,CatalogItem.Kind kind,String code,long revision,UUID parentId,String label){}
    public record Assignment(UUID itemId,long revision,UUID groupId){}
    private final JdbcTemplate jdbc;
    private final TenantAuthorization auth;
    public CatalogGroupService(JdbcTemplate jdbc,TenantAuthorization auth){this.jdbc=jdbc;this.auth=auth;}
    private void admit(VerifiedActor actor,UUID tenant){
        if(actor==null||tenant==null)throw new SecurityException("Verified actor and tenant required");
        auth.requireForCommand(actor.userId(),tenant,DomainPermission.CATALOG_WRITE);
    }
    @Transactional
    public Node appendNode(VerifiedActor actor,UUID tenant,CatalogItem.Kind kind,String code,String label,
                           UUID parent,long expectedRevision){
        admit(actor,tenant);Objects.requireNonNull(kind);
        if(code==null||!code.matches("[A-Z0-9][A-Z0-9._/-]{0,79}")||label==null||label.isBlank()
            ||label.trim().length()>240||expectedRevision<0)
            throw new IllegalArgumentException("CAT_GROUP_INVALID");
        if(parent!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT CASE WHEN count(*)=1 THEN TRUE ELSE FALSE END FROM crm.cat_group_node
            WHERE tenant_id=? AND id=? AND kind=?
            """,Boolean.class,tenant,parent,kind.name())))
            throw new IllegalArgumentException("CAT_GROUP_PARENT_NOT_FOUND");
        long actual=jdbc.queryForObject("""
            SELECT COALESCE(MAX(revision),0) FROM crm.cat_group_node WHERE tenant_id=? AND kind=? AND group_code=?
            """,Long.class,tenant,kind.name(),code);
        if(actual!=expectedRevision)throw new IllegalStateException("CAT_GROUP_STALE_REVISION");
        // Prevent repeating a logical code in its own ancestor chain, including old revisions.
        UUID ancestor=parent;
        for(int depth=0;ancestor!=null;depth++){
            if(depth>=31)throw new IllegalArgumentException("CAT_GROUP_DEPTH_LIMIT");
            var row=jdbc.queryForMap("SELECT group_code,parent_id FROM crm.cat_group_node WHERE tenant_id=? AND id=?",tenant,ancestor);
            if(code.equals(row.get("group_code")))throw new IllegalArgumentException("CAT_GROUP_CYCLE");
            ancestor=(UUID)row.get("parent_id");
        }
        var node=new Node(UUID.randomUUID(),kind,code,Math.incrementExact(actual),parent,label.trim());
        jdbc.update("""
            INSERT INTO crm.cat_group_node(tenant_id,id,kind,group_code,revision,parent_id,label)
            VALUES (?,?,?,?,?,?,?)
            """,tenant,node.id(),kind.name(),code,node.revision(),parent,node.label());
        actor.userId();return node;
    }
    @Transactional
    public Assignment assign(VerifiedActor actor,UUID tenant,UUID item,UUID group,long expectedRevision){
        admit(actor,tenant);Objects.requireNonNull(item);Objects.requireNonNull(group);
        if(expectedRevision<0)throw new IllegalArgumentException("CAT_ASSIGNMENT_REVISION");
        String kind=jdbc.query("SELECT kind FROM crm.cat_item WHERE tenant_id=? AND id=? FOR UPDATE",
            rs->rs.next()?rs.getString(1):null,tenant,item);
        if(kind==null)throw new IllegalArgumentException("CAT_ASSIGNMENT_ITEM_NOT_FOUND");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT CASE WHEN count(*)=1 THEN TRUE ELSE FALSE END FROM crm.cat_group_node
            WHERE tenant_id=? AND id=? AND kind=?
            """,Boolean.class,tenant,group,kind)))throw new IllegalArgumentException("CAT_ASSIGNMENT_GROUP_NOT_FOUND");
        long actual=jdbc.queryForObject("SELECT COALESCE(MAX(revision),0) FROM crm.cat_group_assignment WHERE tenant_id=? AND item_id=?",
            Long.class,tenant,item);
        if(actual!=expectedRevision)throw new IllegalStateException("CAT_ASSIGNMENT_STALE_REVISION");
        var a=new Assignment(item,Math.incrementExact(actual),group);
        jdbc.update("INSERT INTO crm.cat_group_assignment(tenant_id,item_id,revision,kind,group_id) VALUES (?,?,?,?,?)",
            tenant,item,a.revision(),kind,group);
        actor.userId();return a;
    }
}
