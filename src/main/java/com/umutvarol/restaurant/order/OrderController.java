package com.umutvarol.restaurant.order;

import com.umutvarol.restaurant.common.web.PageResponse;
import com.umutvarol.restaurant.order.dto.CancelOrderRequest;
import com.umutvarol.restaurant.order.dto.DiscountRequest;
import com.umutvarol.restaurant.order.dto.LineAdjustmentRequest;
import com.umutvarol.restaurant.order.dto.OrderResponse;
import com.umutvarol.restaurant.order.dto.OrderSearchCriteria;
import com.umutvarol.restaurant.order.dto.PlaceOrderRequest;
import com.umutvarol.restaurant.order.dto.QuoteRequest;
import com.umutvarol.restaurant.order.dto.QuoteResponse;
import com.umutvarol.restaurant.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Tag(name = "Orders", description = "Price carts, place orders and move them through the kitchen")
@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private final OrderService orderService;
    private final OrderAdjustmentService adjustments;

    OrderController(OrderService orderService, OrderAdjustmentService adjustments) {
        this.orderService = orderService;
        this.adjustments = adjustments;
    }

    @Operation(summary = "Search orders",
            description = "Filter by status (repeatable), type, free text (order number, customer, phone, table) and date range.")
    @GetMapping
    PageResponse<OrderResponse> search(
            @RequestParam(name = "status", required = false) List<OrderStatus> statuses,
            @RequestParam(required = false) OrderType type,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return orderService.search(new OrderSearchCriteria(statuses, type, query, from, to), pageable);
    }

    @Operation(summary = "Orders in progress", description = "Every order that is not completed or cancelled, oldest first. Powers the kitchen display.")
    @GetMapping("/active")
    List<OrderResponse> active() {
        return orderService.activeOrders();
    }

    @Operation(summary = "Get an order")
    @GetMapping("/{id}")
    OrderResponse get(@PathVariable Long id) {
        return orderService.get(id);
    }

    @Operation(summary = "Price a cart", description = "Returns totals without placing an order. Invalid coupons are reported, not rejected.")
    @PostMapping("/quote")
    QuoteResponse quote(@Valid @RequestBody QuoteRequest request) {
        return orderService.quote(request);
    }

    @Operation(summary = "Place an order", description = "Requires WAITER or ADMIN.")
    @PostMapping
    ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request, Authentication authentication) {
        OrderResponse order = orderService.placeOrder(request, authentication.getName());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(order.id()).toUri();
        return ResponseEntity.created(location).body(order);
    }

    @Operation(summary = "Advance an order to its next status",
            description = "PENDING → PREPARING → READY → SERVED (dine-in) / OUT_FOR_DELIVERY (delivery) → COMPLETED")
    @PostMapping("/{id}/advance")
    OrderResponse advance(@PathVariable Long id, Authentication authentication) {
        return orderService.advance(id, authentication.getName());
    }

    @Operation(summary = "Cancel an order", description = "Only PENDING or PREPARING orders can be cancelled. Requires WAITER or ADMIN.")
    @PostMapping("/{id}/cancel")
    OrderResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelOrderRequest request,
                         Authentication authentication) {
        return orderService.cancel(id, request.reason(), authentication.getName());
    }

    @Operation(summary = "Void or comp units of a line", description = """
            VOID takes units off; COMP serves them on the house. A waiter needs a manager's PIN in `approval`, except
            for a void while the order is still PENDING. Refused if the bill is already paid beyond the new total.""")
    @PostMapping("/{id}/lines/{lineId}/adjust")
    OrderResponse adjustLine(@PathVariable Long id, @PathVariable Long lineId,
                             @Valid @RequestBody LineAdjustmentRequest request, Authentication authentication,
                             HttpServletRequest http) {
        return adjustments.adjustLine(id, lineId, request, actor(authentication, http));
    }

    @Operation(summary = "Set or remove a manual discount", description = """
            A percentage or an amount, on top of any coupon; send neither to remove it. Needs a manager.""")
    @PutMapping("/{id}/discount")
    OrderResponse discount(@PathVariable Long id, @Valid @RequestBody DiscountRequest request,
                           Authentication authentication, HttpServletRequest http) {
        return adjustments.discount(id, request, actor(authentication, http));
    }

    private static OrderAdjustmentService.Actor actor(Authentication authentication, HttpServletRequest http) {
        boolean manager = authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + Role.ADMIN.name()).equals(authority.getAuthority()));
        return new OrderAdjustmentService.Actor(authentication.getName(), manager, http.getRemoteAddr());
    }
}
