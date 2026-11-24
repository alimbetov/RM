package kz.rm.autoorder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** W1 default-fail-closed admission, independent of QR-03 legacy regression profile. */
@SpringBootTest
@TestPropertySource(properties={
    "RM_KMS_ROOT_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_ENCRYPTION_KEY_BASE64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "RM_IAM_EMAIL_LOOKUP_KEY_BASE64=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
})
class QrLegacyEmailFirstDisabledQualificationTest {
    @Autowired PublishedQrCheckoutService publishedQr;
    @Autowired AutoOrderService auto;

    @Test void qrMvpLegacyServiceEntryPointsRejectEmailOnlyBuyerByDefault() {
        String publicRef="A".repeat(32);
        assertThatThrownBy(()->publishedQr.checkout(publicRef,"email@example.kz",1,
                0,new BigDecimal("1000"),"KZT","QR_LEGACY_BLOCK_01"))
                .isInstanceOf(SecurityException.class)
                .hasMessage("QR_VERIFIED_BUYER_REQUIRED");
        UUID offer=UUID.randomUUID();
        assertThatThrownBy(()->auto.checkoutPublishedQr("legacy-shop","email@example.kz",
                "QR_LEGACY_BLOCK_02",new AutoOrderService.Line(offer,1),
                new AutoOrderService.QrPriceAcknowledgement(publicRef,offer,
                        0,new BigDecimal("1000"),"KZT")))
                .isInstanceOf(SecurityException.class)
                .hasMessage("QR_VERIFIED_BUYER_REQUIRED");
    }
}
