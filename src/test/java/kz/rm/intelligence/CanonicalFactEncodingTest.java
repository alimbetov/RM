package kz.rm.intelligence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CanonicalFactEncodingTest {
    private static final Instant OCCURRED=Instant.parse("2026-01-02T03:04:05.123456Z");
    private static final Instant RECORDED=Instant.parse("2026-01-02T03:04:06Z");
    private static UUID id(int n){return new UUID(0,n);}

    @Test void fixedSalesV1V2GoldenVectors(){
        var key=new CanonicalFactKey(id(1),CanonicalFactKey.Owner.SALES,CanonicalFactKey.Kind.ORDER_PLACED,id(2));
        String legacy=CanonicalFactAppender.legacyDigest(key,id(3),OCCURRED,null,"PLACED");
        assertThat(legacy).isEqualTo("d7360412b6c2b662b2411d7f8c9dfa96f0509316aa1ff0e7d9e1123667d5bb0a");
        String line=CanonicalFactAppender.frame(id(4),id(5),id(6),2,new BigDecimal("12.3400"),"KZT","LIST",null);
        String extended=CanonicalFactAppender.frame(
            CanonicalFactAppender.frame(new BigDecimal("24.6800"),"KZT"),CanonicalFactAppender.frame(line));
        assertThat(CanonicalFactAppender.v2Digest(legacy,extended))
            .isEqualTo("2f1a4e735d8285a102328e15910f3368c2f1f61356a5e0d8bab274cd18020c59");
    }

    @Test void fixedInventoryV1V2GoldenVectors(){
        var key=new CanonicalFactKey(id(1),CanonicalFactKey.Owner.INVENTORY,CanonicalFactKey.Kind.STOCK_MOVEMENT,id(2));
        String legacy=CanonicalFactAppender.legacyDigest(key,id(3),OCCURRED,RECORDED,id(7)+"|-2.5|10|7.5");
        assertThat(legacy).isEqualTo("eb9c0d2c8a872914c21486b38186efe0efdcfff5e22a7b2b8f83d596e8368cb8");
        String extended=CanonicalFactAppender.frame(1L,"ISSUE","FULFILLMENT","SALES_ORDER",id(8),id(9),id(10),null,"key|one");
        assertThat(CanonicalFactAppender.v2Digest(legacy,extended))
            .isEqualTo("497fa14e0d99b0ae4b726a97d2ec63b733bd6b5f13b04e660aef401ca8fdee9a");
    }
    @Test void framingDistinguishesNullEmptyAndDelimiters(){
        assertThat(CanonicalFactAppender.frame((Object)null)).isNotEqualTo(CanonicalFactAppender.frame(""));
        assertThat(CanonicalFactAppender.frame("a|b","c")).isNotEqualTo(CanonicalFactAppender.frame("a","b|c"));
        assertThat(CanonicalFactAppender.frame("ab","c")).isNotEqualTo(CanonicalFactAppender.frame("a","bc"));
    }
    @Test void decimalScaleIsCanonical(){
        assertThat(CanonicalFactAppender.frame(new BigDecimal("12.3400")))
            .isEqualTo(CanonicalFactAppender.frame(new BigDecimal("12.34")));
    }
}
