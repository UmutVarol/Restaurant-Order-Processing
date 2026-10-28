package com.umutvarol.restaurant.fiscal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class FiscalRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");

    @Test
    void taxNumbersAreCheckedWithTheirCheckDigits() {
        assertThat(TaxIds.requireValid("10000000146")).isEqualTo("10000000146");
        assertThat(TaxIds.requireValid(" 1234567890 ")).isEqualTo("1234567890");
        assertThat(TaxIds.isTckn("10000000147")).isFalse();
        assertThat(TaxIds.isTckn("01234567890")).isFalse();
        assertThat(TaxIds.isVkn("1234567891")).isFalse();
        assertThatThrownBy(() -> TaxIds.requireValid("12345")).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("TAX_ID_INVALID");
        assertThat(TaxIds.masked("10000000146")).isEqualTo("••••••••146");
    }

    @Test
    void aFailedDocumentIsRetriedWithGrowingPausesAndThenLeftToAPerson() {
        FiscalDocument document = FiscalDocument.forBill(1L, FiscalKind.RECEIPT, NOW);
        document.due(new BigDecimal("100.00"), new BigDecimal("9.09"), NOW);
        document.failed("bridge offline", NOW);
        assertThat(document.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        document.failed("bridge offline", NOW);
        assertThat(document.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        for (int i = 2; i < FiscalDocument.MAX_ATTEMPTS - 1; i++) {
            document.failed("bridge offline", NOW);
        }
        assertThat(document.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(60)));
        document.failed("bridge offline", NOW);
        assertThat(document.getAttempts()).isEqualTo(FiscalDocument.MAX_ATTEMPTS);
        assertThat(document.getNextAttemptAt()).isNull();
    }

    @Test
    void anIssuedDocumentNeverChanges() {
        FiscalDocument document = FiscalDocument.forBill(1L, FiscalKind.RECEIPT, NOW);
        document.issued("SIMULATED", "ref", "F000001", null, NOW);
        assertThatThrownBy(() -> document.failed("late", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> document.buyer("Someone", "10000000146", null)).isInstanceOf(IllegalStateException.class);
    }
}
