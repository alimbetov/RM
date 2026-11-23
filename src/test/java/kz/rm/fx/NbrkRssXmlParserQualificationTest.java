package kz.rm.fx;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

class NbrkRssXmlParserQualificationTest {
    private final NbrkRssXmlParser parser=new NbrkRssXmlParser();
    private static final LocalDate DATE=LocalDate.of(2026,11,23);
    private static final String USD="<item><title>USD</title><pubDate>23.11.2026</pubDate>"
            +"<description>500.25</description><quant>1</quant><index>UP</index></item>";
    private static final String CNY="<item><title>CNY</title><pubDate>23.11.26</pubDate>"
            +"<description>703,50</description><quant>10</quant></item>";

    private static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] rss(String items) {
        return bytes("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                +"<rss version=\"2.0\"><channel><title>Official exchange rates</title>"
                +items+"</channel></rss>");
    }

    @Test void nbrk01ParsesUsdAndCnyNominalWithoutConvertingToPublicPrices() {
        var rates=parser.parse(rss(USD+CNY),DATE);
        assertThat(rates).hasSize(2);
        assertThat(rates).allMatch(x->"NBRK_RSS".equals(x.source())
                && "NBRK".equals(x.rate().provider()));
        var cny=rates.stream().filter(x->x.rate().currency().equals("CNY"))
                .findFirst().orElseThrow().rate();
        assertThat(cny.nominal()).isEqualByComparingTo("10");
        assertThat(cny.rateKzt()).isEqualByComparingTo("703.50");
        assertThat(cny.rateKzt().divide(cny.nominal()))
                .isEqualByComparingTo("70.35");
    }

    @Test void nbrk02RejectsDtdAndExternalEntity() {
        String evil="<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                +"<rss><channel>"+USD+"</channel></rss>";
        assertThatThrownBy(()->parser.parse(bytes(evil),DATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nbrk03RejectsDuplicateCurrencyOrMissingNominal() {
        assertThatThrownBy(()->parser.parse(rss(USD+USD),DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NBRK_XML_DUPLICATE_CURRENCY");
        assertThatThrownBy(()->parser.parse(rss(USD.replace("<quant>1</quant>","")),DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NBRK_XML_FIELD_REQUIRED");
    }

    @Test void nbrk04RejectsFutureOrMalformedEffectiveDate() {
        assertThatThrownBy(()->parser.parse(rss(USD),DATE.minusDays(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NBRK_XML_FUTURE_EFFECTIVE_DATE");
        assertThatThrownBy(()->parser.parse(rss(USD.replace("23.11.2026","31.02.2026")),DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NBRK_XML_DATE_INVALID");
    }

    @Test void nbrk05RejectsZeroNegativeAndOversizedRates() {
        for(String rate:new String[]{"0","-12.34","9999999999999999999999999999999","NaN"}) {
            assertThatThrownBy(()->parser.parse(rss(
                    USD.replace("500.25",rate)),DATE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void nbrk06RejectsUnexpectedRootAndOversizedXml() {
        assertThatThrownBy(()->parser.parse(bytes("<other/>"),DATE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("NBRK_XML_EXPECTED_RSS");
        assertThatThrownBy(()->parser.parse(new byte[300000],DATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nbrk07RejectsMissingOrDuplicateRequiredField() {
        assertThatThrownBy(()->parser.parse(rss(
                USD.replace("<title>USD</title>","<title>USD</title><title>EUR</title>")),DATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->parser.parse(rss(
                USD.replace("<description>500.25</description>","<description>500.25</description><description>505</description>")),DATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nbrk08DoesNotProduceAnyDatabaseOrOfferUpdate() {
        var rows=parser.parse(rss(USD),DATE);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().rate().validDate()).isEqualTo(DATE);
        assertThat(rows.stream().map(x->x.rate().currency()).collect(Collectors.toList()))
                .containsExactly("USD");
    }
}
