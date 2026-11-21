package kz.rm.crm.dto;

public record CustomerSearch(String type,String status,String regionZoneId,String companyNameContains) {
    public CustomerSearch {
        if(type!=null && !type.matches("PERSON|COMPANY"))
            throw new IllegalArgumentException("Invalid customer type");
        if(status!=null && !status.matches("ACTIVE|ARCHIVED"))
            throw new IllegalArgumentException("Invalid customer status");
        if(regionZoneId!=null && !regionZoneId.matches("[0-9]{2}"))
            throw new IllegalArgumentException("Invalid region zone");
        if(companyNameContains!=null
                && (companyNameContains.isBlank() || companyNameContains.length()>100))
            throw new IllegalArgumentException("Invalid company name filter");
    }
}
