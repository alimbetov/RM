package kz.rm.intelligence;

import java.util.Locale;
import java.util.UUID;

/** Reserved internal consumer identities; no generic handler can claim them. */
enum ProjectionKind {
    L1, L2;
    String prefix(){return name().toLowerCase(Locale.ROOT)+":";}
    String consumer(UUID generation){return prefix()+generation;}
    static boolean reserved(String code){
        String normalized=code.toLowerCase(Locale.ROOT);
        for(var k:values())if(normalized.startsWith(k.prefix()))return true;
        return false;
    }
}
