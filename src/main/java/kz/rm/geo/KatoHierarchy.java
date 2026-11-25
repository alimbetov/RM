package kz.rm.geo;

import java.util.HashSet;
import java.util.function.Function;

/** Shared directory/admission predicate, not a replacement for warehouse RBAC. */
final class KatoHierarchy {
    private KatoHierarchy(){}
    static boolean eligible(KatoTerritoryEntity initial,Function<Long,KatoTerritoryEntity> parentLookup){
        if(initial==null||initial.getCode()==null||!initial.getCode().matches("[0-9]{9}"))return false;
        var seen=new HashSet<Long>();
        var prefix=initial.getCode().substring(0,2);
        int previous=Integer.MAX_VALUE;
        var node=initial;
        for(int depth=0;node!=null&&depth<32;depth++){
            if(node.isDeleted()||node.getTerritoryType()==null||!node.getTerritoryType().locationEligible()
                ||node.getSemanticRelease()==null||!initial.getSemanticRelease().equals(node.getSemanticRelease())
                ||!seen.add(node.getSourceId())||node.getLevel()>=previous
                ||!node.getCode().startsWith(prefix))return false;
            if(node.getTerritoryType().regionRoot())return node.getParentSourceId()==null;
            if(node.getParentSourceId()==null)return false;
            previous=node.getLevel();
            node=parentLookup.apply(node.getParentSourceId());
        }
        return false;
    }
}
