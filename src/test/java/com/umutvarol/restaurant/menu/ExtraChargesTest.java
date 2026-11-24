package com.umutvarol.restaurant.menu;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExtraChargesTest {

    @ParameterizedTest
    @ValueSource(strings = {"Servis Ücreti", "SERVİS ÜCRETİ", "servis ucreti %10", "Hizmet bedeli", "Kuver", "Kuvert",
            "Masa Ücreti", "Cover charge", "Service fee", "Couvert"})
    void namesOfForbiddenChargesAreRecognised(String name) {
        assertThat(ExtraCharges.isFee(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Kahvaltı servisi", "Servis tabağı", "Masa tuzu", "Kuverli değil", "Hizmetçi kızın tatlısı", "Ayran"})
    void ordinaryDishesAreNot(String name) {
        assertThat(ExtraCharges.isFee(name)).isFalse();
    }
}
