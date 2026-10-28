package com.umutvarol.restaurant.order.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.order.dto.OrderLineRequest;
import com.umutvarol.restaurant.order.dto.PlaceOrderRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FulfilmentValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void dineInOrdersRequireATable() {
        assertThat(violatedFields(request(OrderType.DINE_IN, null, null, null))).containsExactly("tableId");
        assertThat(violatedFields(request(OrderType.DINE_IN, 4L, null, null))).isEmpty();
    }

    @Test
    void deliveryOrdersRequireAnAddressAndAPhoneNumber() {
        assertThat(violatedFields(request(OrderType.DELIVERY, null, " ", null)))
                .containsExactlyInAnyOrder("deliveryAddress", "customerPhone");
        assertThat(violatedFields(request(OrderType.DELIVERY, null, "Moda Cad. 12", "+90 532 111 22 33"))).isEmpty();
    }

    @Test
    void takeawayOrdersNeedNeither() {
        assertThat(violatedFields(request(OrderType.TAKEAWAY, null, null, null))).isEmpty();
    }

    @Test
    void rejectsMalformedPhoneNumbers() {
        assertThat(violatedFields(request(OrderType.TAKEAWAY, null, null, "call me maybe"))).containsExactly("customerPhone");
    }

    private static Set<String> violatedFields(PlaceOrderRequest request) {
        Set<ConstraintViolation<PlaceOrderRequest>> violations = validator.validate(request);
        return violations.stream().map(v -> v.getPropertyPath().toString())
                .collect(java.util.stream.Collectors.toSet());
    }

    private static PlaceOrderRequest request(OrderType type, Long tableId, String address, String phone) {
        return new PlaceOrderRequest(type, "Ayşe", phone, tableId, address, null, PaymentMethod.CARD, null,
                List.of(new OrderLineRequest(1L, 1, null, null)));
    }
}
