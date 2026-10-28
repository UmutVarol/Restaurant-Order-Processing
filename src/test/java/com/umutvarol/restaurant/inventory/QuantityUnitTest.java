package com.umutvarol.restaurant.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class QuantityUnitTest {

    @Test
    void convertsExactlyIntoTheBaseUnit() {
        assertThat(QuantityUnit.KG.toBase(new BigDecimal("1.2345"), StockUnit.GRAM)).isEqualByComparingTo("1234.5");
        assertThat(QuantityUnit.L.toBase(new BigDecimal("0.333"), StockUnit.MILLILITRE)).isEqualByComparingTo("333");
        assertThat(QuantityUnit.G.toBase(new BigDecimal("0.0004"), StockUnit.GRAM)).isEqualByComparingTo("0");
        assertThat(QuantityUnit.PIECE.toBase(new BigDecimal("0.25"), StockUnit.PIECE)).isEqualByComparingTo("0.25");
    }

    @Test
    void refusesUnitsOfAnotherKind() {
        assertThatThrownBy(() -> QuantityUnit.L.toBase(BigDecimal.ONE, StockUnit.GRAM))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("UNIT_MISMATCH");
        assertThatThrownBy(() -> QuantityUnit.KG.toBase(BigDecimal.ONE, StockUnit.PIECE))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void valuesBaseQuantitiesAtThePricePerBulkUnit() {
        Ingredient butter = new Ingredient("Butter", StockUnit.GRAM, new BigDecimal("520.00"), new BigDecimal("100"), true);
        assertThat(butter.valueOf(new BigDecimal("250"))).isEqualByComparingTo("130");
        assertThat(butter.isLow(new BigDecimal("100"))).isTrue();
        assertThat(butter.isOut(BigDecimal.ZERO)).isTrue();
        Ingredient salt = new Ingredient("Salt", StockUnit.GRAM, BigDecimal.TEN, null, false);
        assertThat(salt.isOut(new BigDecimal("-5"))).isFalse();
    }
}
