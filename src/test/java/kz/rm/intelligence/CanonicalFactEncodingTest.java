package kz.rm.intelligence;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CanonicalFactEncodingTest {
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
