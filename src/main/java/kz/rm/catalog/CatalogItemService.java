package kz.rm.catalog;

import kz.rm.iam.DomainPermission;
import kz.rm.iam.TenantAuthorization;
import kz.rm.iam.VerifiedActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Tenant-owned CATALOG commands, using the existing cat_item/i18n/price tables.
 * A published SKU remains a stable identity; prices are append-only revisions.
 */
@Service
public class CatalogItemService {
    public record Item(UUID tenantId, UUID id, String sku, String modelCode,
                       CatalogItem.Kind kind, String sourceLocale,
                       CatalogItem.State state, long rowVersion) {}

    private final JdbcTemplate jdbc;
    private final TenantAuthorization auth;

    public CatalogItemService(JdbcTemplate jdbc,TenantAuthorization auth) {
        this.jdbc=jdbc;this.auth=auth;
    }

    private UUID admit(VerifiedActor actor, UUID tenant, DomainPermission permission) {
        if(actor==null || tenant==null) throw new SecurityException("Verified actor and tenant required");
        auth.requireForCommand(actor.userId(),tenant,permission);
        return actor.userId();
    }

    @Transactional
    public Item createDraft(VerifiedActor actor, UUID tenant, String sku, CatalogItem.Kind kind,
                            String sourceLocale, String sourceName, String modelCode) {
        UUID who=admit(actor,tenant,DomainPermission.CATALOG_WRITE);
        if(kind==null) throw new IllegalArgumentException("Catalog kind required");
        sku=code(sku,true);
        modelCode=code(modelCode,false);
        sourceLocale=locale(sourceLocale);
        sourceName=title(sourceName);
        UUID item=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO crm.cat_item
                  (tenant_id,id,sku,model_code,kind,source_locale,state,created_at,row_version)
                VALUES (?,?,?,?,?,?,'DRAFT',?,0)
                """,tenant,item,sku,modelCode,kind.name(),sourceLocale,
                Timestamp.from(Instant.now()));
        jdbc.update("""
                INSERT INTO crm.cat_item_i18n(tenant_id,item_id,locale,name,description)
                VALUES (?,?,?,?,NULL)
                """,tenant,item,sourceLocale,sourceName);
        event(tenant,item,who,"CREATED",null,0);
        return fetch(tenant,item,false);
    }

    @Transactional
    public Item updateModelCode(VerifiedActor actor,UUID tenant,UUID itemId,
                                String modelCode,long expectedVersion) {
        UUID who=admit(actor,tenant,DomainPermission.CATALOG_WRITE);
        Item item=lockedDraft(tenant,itemId,expectedVersion);
        String model=code(modelCode,false);
        update(tenant,item.id(),expectedVersion,"model_code",model);
        event(tenant,item.id(),who,"MODEL_UPDATED",expectedVersion,expectedVersion+1);
        return fetch(tenant,item.id(),false);
    }

    @Transactional
    public Item upsertTranslation(VerifiedActor actor,UUID tenant,UUID itemId,
                                  String locale,String name,long expectedVersion) {
        UUID who=admit(actor,tenant,DomainPermission.CATALOG_WRITE);
        Item item=lockedDraft(tenant,itemId,expectedVersion);
        String targetLocale=locale(locale);
        String normalized=title(name);
        int changed=jdbc.update("""
                UPDATE crm.cat_item_i18n SET name=?
                WHERE tenant_id=? AND item_id=? AND locale=?
                """,normalized,tenant,item.id(),targetLocale);
        if(changed==0)jdbc.update("""
                INSERT INTO crm.cat_item_i18n(tenant_id,item_id,locale,name,description)
                VALUES (?,?,?,?,NULL)
                """,tenant,item.id(),targetLocale,normalized);
        bump(tenant,item.id(),expectedVersion);
        event(tenant,item.id(),who,"TRANSLATION_UPDATED",expectedVersion,expectedVersion+1);
        return fetch(tenant,item.id(),false);
    }

    @Transactional
    public Item addPriceRevision(VerifiedActor actor,UUID tenant,UUID itemId,
                                 BigDecimal amount,String currency,Instant validFrom,
                                 long expectedVersion) {
        UUID who=admit(actor,tenant,DomainPermission.CATALOG_PRICE_MANAGE);
        Item item=locked(tenant,itemId,expectedVersion);
        if(item.state()==CatalogItem.State.ARCHIVED)
            throw new IllegalStateException("Cannot price archived catalog item");
        if(amount==null || amount.signum()<0 || amount.scale()>4 ||
                currency==null || !currency.matches("[A-Z]{3}") || validFrom==null)
            throw new IllegalArgumentException("Invalid effective price");
        Integer same=jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_price
                WHERE tenant_id=? AND item_id=? AND valid_from=?
                """,Integer.class,tenant,item.id(),Timestamp.from(validFrom));
        if(same!=null && same>0)
            throw new IllegalArgumentException("Price revision already exists at validFrom");
        jdbc.update("""
                INSERT INTO crm.cat_price(tenant_id,id,item_id,unit_price,currency,valid_from,created_at)
                VALUES (?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),item.id(),amount,currency,
                Timestamp.from(validFrom),Timestamp.from(Instant.now()));
        bump(tenant,item.id(),expectedVersion);
        event(tenant,item.id(),who,"PRICE_REVISED",expectedVersion,expectedVersion+1);
        return fetch(tenant,item.id(),false);
    }

    @Transactional
    public Item publish(VerifiedActor actor,UUID tenant,UUID itemId,long expectedVersion) {
        UUID who=admit(actor,tenant,DomainPermission.CATALOG_PUBLISH);
        Item item=lockedDraft(tenant,itemId,expectedVersion);
        Integer titleCount=jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_item_i18n
                WHERE tenant_id=? AND item_id=? AND locale=? AND length(trim(name))>0
                """,Integer.class,tenant,item.id(),item.sourceLocale());
        Integer priceCount=jdbc.queryForObject("""
                SELECT count(*) FROM crm.cat_price
                WHERE tenant_id=? AND item_id=? AND valid_from<=?
                """,Integer.class,tenant,item.id(),Timestamp.from(Instant.now()));
        if(titleCount==null||titleCount<1||priceCount==null||priceCount<1)
            throw new IllegalStateException("Publication requires source translation and effective price");
        update(tenant,item.id(),expectedVersion,"state",CatalogItem.State.PUBLISHED.name());
        event(tenant,item.id(),who,"PUBLISHED",expectedVersion,expectedVersion+1);
        return fetch(tenant,item.id(),false);
    }

    @Transactional
    public Item archive(VerifiedActor actor,UUID tenant,UUID itemId,long expectedVersion) {
        UUID who=admit(actor,tenant,DomainPermission.CATALOG_PUBLISH);
        Item item=locked(tenant,itemId,expectedVersion);
        if(item.state()==CatalogItem.State.ARCHIVED)
            throw new IllegalStateException("Item already archived");
        update(tenant,item.id(),expectedVersion,"state",CatalogItem.State.ARCHIVED.name());
        event(tenant,item.id(),who,"ARCHIVED",expectedVersion,expectedVersion+1);
        return fetch(tenant,item.id(),false);
    }

    private Item lockedDraft(UUID tenant,UUID id,long expectedVersion) {
        Item item=locked(tenant,id,expectedVersion);
        if(item.state()!=CatalogItem.State.DRAFT)
            throw new IllegalStateException("Only draft catalog items can change metadata/translations");
        return item;
    }

    private Item locked(UUID tenant,UUID id,long expectedVersion) {
        if(id==null||expectedVersion<0)throw new IllegalArgumentException("Catalog item/version required");
        Item item=fetch(tenant,id,true);
        if(item.rowVersion()!=expectedVersion)
            throw new IllegalStateException("Stale catalog version");
        return item;
    }

    private Item fetch(UUID tenant,UUID id,boolean lock) {
        if(id==null)throw new IllegalArgumentException("Catalog item ID required");
        String sql="""
                SELECT tenant_id,id,sku,model_code,kind,source_locale,state,row_version
                FROM crm.cat_item WHERE tenant_id=? AND id=?
                """+(lock?" FOR UPDATE":"");
        var list=jdbc.query(sql,(rs,i)->map(rs),tenant,id);
        if(list.isEmpty())throw new IllegalArgumentException("Catalog item not found in tenant");
        return list.getFirst();
    }

    static Item map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Item(rs.getObject("tenant_id",UUID.class),rs.getObject("id",UUID.class),
                rs.getString("sku"),rs.getString("model_code"),
                CatalogItem.Kind.valueOf(rs.getString("kind")),rs.getString("source_locale"),
                CatalogItem.State.valueOf(rs.getString("state")),rs.getLong("row_version"));
    }

    private void update(UUID tenant,UUID id,long expected,String column,String value) {
        if(!column.equals("state")&&!column.equals("model_code"))
            throw new IllegalArgumentException("Unknown catalog column");
        int changed=jdbc.update("UPDATE crm.cat_item SET "+column+
                "=?,row_version=row_version+1 WHERE tenant_id=? AND id=? AND row_version=?",
                value,tenant,id,expected);
        if(changed!=1)throw new IllegalStateException("Concurrent catalog update");
    }

    private void bump(UUID tenant,UUID id,long expected) {
        int updated=jdbc.update("""
                UPDATE crm.cat_item SET row_version=row_version+1
                WHERE tenant_id=? AND id=? AND row_version=?
                """,tenant,id,expected);
        if(updated!=1)throw new IllegalStateException("Concurrent catalog revision");
    }

    private void event(UUID tenant,UUID item,UUID actor,String action,Long previous,long next) {
        jdbc.update("""
                INSERT INTO crm.cat_item_event
                  (tenant_id,id,item_id,actor_id,action_code,previous_version,new_version,occurred_at)
                VALUES (?,?,?,?,?,?,?,?)
                """,tenant,UUID.randomUUID(),item,actor,action,previous,next,
                Timestamp.from(Instant.now()));
    }

    private static String title(String source) {
        if(source==null||source.isBlank()||source.trim().length()>240)
            throw new IllegalArgumentException("Catalog title must be 1..240 chars");
        return source.trim();
    }
    private static String locale(String locale) {
        if(!"ru".equals(locale)&&!"kk".equals(locale)&&!"en".equals(locale)
                &&!"zh-Hans".equals(locale))
            throw new IllegalArgumentException("Unsupported catalog locale");
        return locale;
    }
    private static String code(String code, boolean required) {
        if(code==null){
            if(required)throw new IllegalArgumentException("SKU required");
            return null;
        }
        String normalized=java.text.Normalizer.normalize(code.trim(),java.text.Normalizer.Form.NFC)
                .toUpperCase(Locale.ROOT);
        if(!normalized.matches("[A-Z0-9][A-Z0-9._/-]{0,79}"))
            throw new IllegalArgumentException("Invalid SKU/model code");
        return normalized;
    }
}
