package kz.rm.geo;

/**
 * UI-oriented KATO dictionary entry. Code is a technical reference, not a
 * secret; clients should show names rather than the numeric code.
 */
public record KatoTerritoryView(
        String code, String nameKaz, String nameRus, String nameEng, int level, boolean deleted,
        KatoTerritoryType territoryType) {
    public static KatoTerritoryView from(KatoTerritoryEntity e) {
        return new KatoTerritoryView(e.getCode(), e.getNameKk(), e.getNameRu(),
                e.getNameEng(), e.getLevel(), e.isDeleted(), e.getTerritoryType());
    }
}
