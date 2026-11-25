package kz.rm.organization;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/** MVP mode must not silently fall back to default-off public KZT checks. */
class MvpServiceCurrencyStartupQualificationTest {
    @Test void mvpW2StrictServiceModeCannotStartWithPublicCurrencyAdmissionDisabled() {
        assertThatThrownBy(()->CommerceCurrencyAdmission.requireStrictMvpCurrencyPolicy(true,false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MVP_PUBLIC_KZT_ADMISSION_REQUIRED");
        assertThatCode(()->CommerceCurrencyAdmission.requireStrictMvpCurrencyPolicy(true,true))
                .doesNotThrowAnyException();
        // Historical non-MVP test/maintenance profiles are not release evidence.
        assertThatCode(()->CommerceCurrencyAdmission.requireStrictMvpCurrencyPolicy(false,false))
                .doesNotThrowAnyException();
    }
}
