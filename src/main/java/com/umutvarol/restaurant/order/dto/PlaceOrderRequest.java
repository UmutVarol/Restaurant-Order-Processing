package com.umutvarol.restaurant.order.dto;

import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.order.validation.ValidFulfilment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Request to place an order. Field-level rules are declared on the components; the cross-field rules
 * (a table for dine-in, an address and phone for delivery) are enforced by {@link ValidFulfilment}.
 */
@ValidFulfilment
public record PlaceOrderRequest(
        @NotNull OrderType type,
        @NotBlank @Size(max = 80) String customerName,
        @Pattern(regexp = "^$|^\\+?[0-9 ()-]{7,20}$", message = "must be a valid phone number") String customerPhone,
        Long tableId,
        @Size(max = 255) String deliveryAddress,
        @Size(max = 255) String note,
        @NotNull PaymentMethod paymentMethod,
        @Size(max = 32) String couponCode,
        @NotEmpty @Size(max = 50) List<@Valid @NotNull OrderLineRequest> items) {
}
