package com.umutvarol.restaurant.repricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RoundingTest {

    @Test
    void roundsToTheNearestStepAndNeverToZero() {
        assertThat(Rounding.ONE.round(new BigDecimal("52.03"))).isEqualByComparingTo("52.00");
        assertThat(Rounding.ONE.round(new BigDecimal("52.50"))).isEqualByComparingTo("53.00");
        assertThat(Rounding.HALF.round(new BigDecimal("84.70"))).isEqualByComparingTo("84.50");
        assertThat(Rounding.FIVE.round(new BigDecimal("187.40"))).isEqualByComparingTo("185.00");
        assertThat(Rounding.TEN.round(new BigDecimal("4.00"))).isEqualByComparingTo("10.00");
        assertThat(Rounding.NONE.round(new BigDecimal("84.7049"))).isEqualByComparingTo("84.70");
    }

    @Test
    void roundsUpForASuggestedPrice() {
        assertThat(Rounding.ONE.roundUp(new BigDecimal("183.33"))).isEqualByComparingTo("184.00");
        assertThat(Rounding.FIVE.roundUp(new BigDecimal("181.00"))).isEqualByComparingTo("185.00");
        assertThat(Rounding.NONE.roundUp(new BigDecimal("183.331"))).isEqualByComparingTo("183.34");
    }
}
