package kz.rm.geo;

/** Explicit administrative semantics from an approved mapping of a directory release.
 * This is not the numeric depth or source AreaType. LEGACY_OTHER is not admitted
 * for new organization locations.
 */
public enum KatoTerritoryType {
    OBLAST, REPUBLICAN_CITY, DISTRICT, CITY, CITY_DISTRICT,
    CITY_ADMINISTRATION, DISTRICT_ADMINISTRATION, LEGACY_OTHER;

    public boolean regionRoot(){return this==OBLAST||this==REPUBLICAN_CITY;}
    public boolean locationEligible(){return this!=LEGACY_OTHER;}
}
