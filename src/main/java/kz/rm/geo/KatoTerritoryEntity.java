package kz.rm.geo;

import jakarta.persistence.*;

/** Shared, read-only KATO snapshot. Codes are strings; source IDs define hierarchy. */
@Entity
@Table(name="ref_kato_territory")
public class KatoTerritoryEntity {
    @Id @Column(length=16) private String code;
    @Column(name="source_id",nullable=false,unique=true) private Long sourceId;
    @Column(name="parent_source_id") private Long parentSourceId;
    @Column(name="level_no",nullable=false) private Integer level;
    @Column(name="area_type",nullable=false) private Integer areaType;
    @Column(name="name_kk",nullable=false,length=300) private String nameKk;
    @Column(name="name_ru",nullable=false,length=300) private String nameRu;
    @Column(name="is_deleted",nullable=false) private Boolean deleted;
    protected KatoTerritoryEntity() {}
    public String getCode(){return code;}
    public Long getSourceId(){return sourceId;}
    public Long getParentSourceId(){return parentSourceId;}
    public Integer getLevel(){return level;}
    public Integer getAreaType(){return areaType;}
    public String getNameKk(){return nameKk;}
    public String getNameRu(){return nameRu;}
    public boolean isDeleted(){return Boolean.TRUE.equals(deleted);}
}
