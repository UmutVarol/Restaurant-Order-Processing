package com.umutvarol.restaurant.guest;

import com.umutvarol.restaurant.common.config.RestaurantProperties;
import com.umutvarol.restaurant.common.crypto.SecretTokens;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.error.TooManyRequestsException;
import com.umutvarol.restaurant.guest.dto.GuestOrderRequest;
import com.umutvarol.restaurant.guest.dto.GuestRequestResponse;
import com.umutvarol.restaurant.guest.dto.GuestRequestView;
import com.umutvarol.restaurant.guest.dto.PublicTableResponse;
import com.umutvarol.restaurant.guest.dto.TableQrResponse;
import com.umutvarol.restaurant.order.OrderService;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.order.dto.OrderLineRequest;
import com.umutvarol.restaurant.order.dto.OrderLineResponse;
import com.umutvarol.restaurant.order.dto.OrderResponse;
import com.umutvarol.restaurant.order.dto.PlaceOrderRequest;
import com.umutvarol.restaurant.order.dto.QuoteRequest;
import com.umutvarol.restaurant.order.dto.QuoteResponse;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ordering from the table. Anyone holding a table's QR code can send a cart or call for a waiter, so: the code is
 * an unguessable token stored only as a hash; a cart never reaches the kitchen until a waiter accepts it, and is
 * then placed exactly like an order from the till (same prices, menu and option rules); each table has a small
 * number of open requests and a short pause between carts; repeated calls fold into the one already waiting.
 */
@Service
@Transactional
public class GuestService {

    static final int MAX_OPEN_PER_TABLE = 5;
    static final Duration ORDER_PAUSE = Duration.ofSeconds(15);
    static final Duration CALL_PAUSE = Duration.ofSeconds(30);

    private static final Logger audit = LoggerFactory.getLogger("guest.audit");

    private final GuestRequestRepository requests;
    private final DiningTableRepository tables;
    private final OrderService orders;
    private final RestaurantProperties restaurant;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public GuestService(GuestRequestRepository requests, DiningTableRepository tables, OrderService orders,
                        RestaurantProperties restaurant, ApplicationEventPublisher events, Clock clock) {
        this.requests = requests;
        this.tables = tables;
        this.orders = orders;
        this.restaurant = restaurant;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- guests

    @Transactional(readOnly = true)
    public PublicTableResponse table(String token) {
        DiningTable table = tableFor(token);
        List<GuestRequestKind> openCalls = List.of(GuestRequestKind.CALL_WAITER, GuestRequestKind.REQUEST_BILL).stream()
                .filter(kind -> requests.findFirstByTableIdAndKindAndStatus(table.getId(), kind, GuestRequestStatus.OPEN).isPresent())
                .toList();
        return new PublicTableResponse(restaurant.name(), table.getCode(), openCalls);
    }

    public GuestRequestView order(String token, GuestOrderRequest cart, String client) {
        DiningTable table = tableFor(token);
        Instant now = clock.instant();
        requireRoom(table);
        if (requests.existsByTableIdAndKindAndCreatedAtAfter(table.getId(), GuestRequestKind.ORDER, now.minus(ORDER_PAUSE))) {
            throw new TooManyRequestsException("GUEST_SLOW_DOWN", "A cart was just sent from this table", ORDER_PAUSE);
        }
        List<OrderLineRequest> lines = cart.items().stream()
                .map(item -> new OrderLineRequest(item.menuItemId(), item.quantity(), blankToNull(item.note()),
                        item.optionIds() == null ? List.of() : List.copyOf(item.optionIds())))
                .toList();
        // priced (and every dish and option checked) exactly like the till would
        QuoteResponse quote = orders.quote(new QuoteRequest(OrderType.DINE_IN, null, lines));
        GuestRequest request = GuestRequest.order(table, blankToNull(cart.guestName()), blankToNull(cart.note()), now);
        for (int i = 0; i < lines.size(); i++) {
            OrderLineRequest asked = lines.get(i);
            OrderLineResponse priced = quote.lines().get(i);
            request.addLine(new GuestRequestLine(request, asked.menuItemId(), priced.name(), priced.icon(),
                    asked.quantity(), asked.note(),
                    priced.options().stream().map(OrderLineResponse.Option::choiceId).toList(),
                    priced.options().isEmpty() ? null : priced.options().stream().map(OrderLineResponse.Option::choice)
                            .collect(Collectors.joining(" · ")),
                    priced.lineTotal()));
        }
        request.priced(quote.pricing().total());
        requests.save(request);
        audit.info("guest.order table={} request={} total={} client={}", table.getCode(), request.getId(),
                request.getQuotedTotal(), client);
        events.publishEvent(GuestRequestChangedEvent.of(request, now));
        return GuestRequestView.from(request);
    }

    public GuestRequestView call(String token, GuestRequestKind kind, String client) {
        if (kind == GuestRequestKind.ORDER) {
            throw new BusinessRuleException("GUEST_CALL_INVALID", "Send a cart to order");
        }
        DiningTable table = tableFor(token);
        GuestRequest waiting = requests.findFirstByTableIdAndKindAndStatus(table.getId(), kind, GuestRequestStatus.OPEN)
                .orElse(null);
        if (waiting != null) {
            return GuestRequestView.from(waiting); // pressing again does not ring twice
        }
        Instant now = clock.instant();
        requireRoom(table);
        if (requests.existsByTableIdAndKindAndCreatedAtAfter(table.getId(), kind, now.minus(CALL_PAUSE))) {
            throw new TooManyRequestsException("GUEST_SLOW_DOWN", "The staff were just called from this table", CALL_PAUSE);
        }
        GuestRequest request = requests.save(GuestRequest.call(table, kind, now));
        audit.info("guest.{} table={} request={} client={}", kind.name().toLowerCase(), table.getCode(), request.getId(), client);
        events.publishEvent(GuestRequestChangedEvent.of(request, now));
        return GuestRequestView.from(request);
    }

    /** A guest's phone checking on its own request; the table's token must match too. */
    @Transactional(readOnly = true)
    public GuestRequestView status(String token, String publicId) {
        DiningTable table = tableFor(token);
        return requests.findByPublicId(publicId)
                .filter(request -> request.getTable().getId().equals(table.getId()))
                .map(GuestRequestView::from)
                .orElseThrow(() -> new ResourceNotFoundException("request", "?"));
    }

    // ------------------------------------------------------------------------------------------- staff

    @Transactional(readOnly = true)
    public List<GuestRequestResponse> open() {
        return requests.findByStatusOrderByCreatedAtAsc(GuestRequestStatus.OPEN).stream()
                .map(GuestRequestResponse::from)
                .toList();
    }

    /** Places the guest's cart as a real dine-in order, checked and priced again as of now. */
    public GuestRequestResponse accept(Long id, String actor) {
        GuestRequest request = find(id);
        DiningTable table = request.getTable();
        String customer = request.getGuestName() != null ? request.getGuestName() : "QR " + table.getCode();
        OrderResponse order = orders.placeOrder(new PlaceOrderRequest(OrderType.DINE_IN, customer, null, table.getId(),
                null, request.getNote(), PaymentMethod.CARD, null,
                request.getLines().stream().map(line -> new OrderLineRequest(line.getMenuItemId(), line.getQuantity(),
                        line.getNote(), line.getOptionIds())).toList()), actor);
        Instant now = clock.instant();
        request.accept(order.id(), now, actor);
        audit.info("guest.accepted request={} order={} by={}", request.getId(), order.id(), actor);
        events.publishEvent(GuestRequestChangedEvent.of(request, now));
        return GuestRequestResponse.from(request);
    }

    public GuestRequestResponse reject(Long id, String reason, String actor) {
        GuestRequest request = find(id);
        Instant now = clock.instant();
        request.reject(reason.strip(), now, actor);
        audit.info("guest.rejected request={} by={}", request.getId(), actor);
        events.publishEvent(GuestRequestChangedEvent.of(request, now));
        return GuestRequestResponse.from(request);
    }

    public GuestRequestResponse done(Long id, String actor) {
        GuestRequest request = find(id);
        Instant now = clock.instant();
        request.done(now, actor);
        events.publishEvent(GuestRequestChangedEvent.of(request, now));
        return GuestRequestResponse.from(request);
    }

    /** A new QR code for a table; the one printed before stops working at once. */
    public TableQrResponse issueQr(Long tableId, String actor) {
        DiningTable table = tables.findById(tableId).orElseThrow(() -> new ResourceNotFoundException("table", tableId));
        String token = SecretTokens.newToken();
        Instant now = clock.instant();
        table.issueQrToken(SecretTokens.hash(token), now);
        audit.info("guest.qr_issued table={} by={}", table.getCode(), actor);
        return new TableQrResponse(table.getId(), table.getCode(), token, now);
    }

    private DiningTable tableFor(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            throw new ResourceNotFoundException("table", "?");
        }
        return tables.findByQrTokenHash(SecretTokens.hash(token))
                .orElseThrow(() -> new ResourceNotFoundException("table", "?"));
    }

    private void requireRoom(DiningTable table) {
        if (requests.countByTableIdAndStatus(table.getId(), GuestRequestStatus.OPEN) >= MAX_OPEN_PER_TABLE) {
            throw new TooManyRequestsException("GUEST_TOO_MANY_OPEN",
                    "This table already has several requests waiting", Duration.ofMinutes(1));
        }
    }

    private GuestRequest find(Long id) {
        return requests.findById(id).orElseThrow(() -> new ResourceNotFoundException("guest request", id));
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
