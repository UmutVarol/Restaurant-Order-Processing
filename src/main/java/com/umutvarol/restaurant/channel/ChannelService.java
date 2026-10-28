package com.umutvarol.restaurant.channel;

import com.umutvarol.restaurant.channel.dto.ChannelOrderPayload;
import com.umutvarol.restaurant.channel.dto.ChannelOrderResponse;
import com.umutvarol.restaurant.channel.dto.ChannelResponse;
import com.umutvarol.restaurant.channel.dto.MappingRequest;
import com.umutvarol.restaurant.channel.dto.MappingResponse;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.DomainException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.menu.MenuAvailabilityChangedEvent;
import com.umutvarol.restaurant.menu.MenuService;
import com.umutvarol.restaurant.menu.dto.MenuItemResponse;
import com.umutvarol.restaurant.menu.dto.OptionGroupResponse;
import com.umutvarol.restaurant.order.OrderChangedEvent;
import com.umutvarol.restaurant.order.OrderService;
import com.umutvarol.restaurant.order.OrderType;
import com.umutvarol.restaurant.order.PaymentMethod;
import com.umutvarol.restaurant.order.dto.OrderLineRequest;
import com.umutvarol.restaurant.order.dto.OrderResponse;
import com.umutvarol.restaurant.order.dto.PlaceOrderRequest;
import com.umutvarol.restaurant.order.dto.QuoteRequest;
import com.umutvarol.restaurant.payment.PaymentService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orders from delivery platforms. Each platform order is kept once (its id is unique per platform), placed on the
 * kitchen display and printers like a takeaway order the courier collects, and paid by the platform. An item nobody
 * mapped, an option we do not have or a sold-out dish stops the order for a manager instead of being guessed. Status
 * changes and sold-out dishes go back to the platform.
 *
 * <p>Placing runs in its own transaction: if it fails, the platform order is still kept, marked for attention.
 */
@Service
public class ChannelService {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()-]{7,20}$");
    private static final int LIST_LIMIT = 50;

    private final ChannelOrderRepository orders;
    private final ChannelItemMappingRepository mappings;
    private final ChannelGateway gateway;
    private final OrderService orderService;
    private final PaymentService payments;
    private final MenuService menu;
    private final Validator validator;
    private final ChannelAuditLog audit;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;

    ChannelService(ChannelOrderRepository orders, ChannelItemMappingRepository mappings, ChannelGateway gateway,
                   OrderService orderService, PaymentService payments, MenuService menu, Validator validator,
                   ChannelAuditLog audit, Clock clock, PlatformTransactionManager transactions) {
        this.orders = orders;
        this.mappings = mappings;
        this.gateway = gateway;
        this.orderService = orderService;
        this.payments = payments;
        this.menu = menu;
        this.validator = validator;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactions);
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------------------------------- inbound

    /**
     * A webhook delivery: the signature and timestamp are checked before the body is even read.
     *
     * @throws ResourceNotFoundException for a platform that takes no webhooks (not connected)
     */
    public ChannelOrderResponse receive(Channel channel, byte[] body, @Nullable String timestamp,
                                        @Nullable String signature, String client) {
        String secret = gateway.webhookSecret(channel);
        if (secret == null) {
            audit.refused(channel, "NOT_CONNECTED", client);
            throw new ResourceNotFoundException("channel", channel.name().toLowerCase(Locale.ROOT));
        }
        try {
            WebhookSignature.verify(secret, timestamp, signature, body, clock.instant(), gateway.webhookTolerance());
        } catch (DomainException ex) {
            audit.refused(channel, ex.getCode(), client);
            throw ex;
        }
        return ingest(channel, parse(body));
    }

    /** Keeps the platform order once, then tries to place it. A second delivery of the same order changes nothing. */
    ChannelOrderResponse ingest(Channel channel, ChannelOrderPayload payload) {
        validate(payload);
        Optional<ChannelOrder> existing = orders.findByChannelAndExternalId(channel, payload.id());
        if (existing.isPresent()) {
            audit.received(channel, payload.id(), "DUPLICATE");
            return response(existing.get());
        }
        Long id;
        try {
            id = tx.execute(status -> orders.saveAndFlush(new ChannelOrder(channel, payload.id(), write(payload),
                    payload.total(), clock.instant())).getId());
        } catch (DataIntegrityViolationException raced) {
            audit.received(channel, payload.id(), "DUPLICATE");
            return response(orders.findByChannelAndExternalId(channel, payload.id()).orElseThrow());
        }
        place(id);
        return response(orders.findById(id).orElseThrow());
    }

    /** A manager mapped what was missing (or restocked a dish): try the order again. */
    public ChannelOrderResponse retry(Long id) {
        ChannelOrder record = find(id);
        if (record.getStatus() != ChannelOrder.Status.ATTENTION) {
            throw new InvalidStateException("CHANNEL_ORDER_NOT_WAITING", "This platform order is already " + record.getStatus(),
                    Map.of("status", record.getStatus()));
        }
        place(id);
        return response(orders.findById(id).orElseThrow());
    }

    /** Turns down an order that cannot be made; the platform is told. */
    public ChannelOrderResponse reject(Long id) {
        ChannelOrderResponse rejected = tx.execute(status -> {
            ChannelOrder record = find(id);
            if (record.getStatus() != ChannelOrder.Status.ATTENTION) {
                throw new InvalidStateException("CHANNEL_ORDER_NOT_WAITING", "This platform order is already " + record.getStatus(),
                        Map.of("status", record.getStatus()));
            }
            record.rejected(clock.instant());
            return response(record);
        });
        push(id, ChannelStatus.REJECTED);
        return rejected;
    }

    private void place(Long id) {
        try {
            tx.executeWithoutResult(status -> {
                ChannelOrder record = orders.findById(id).orElseThrow();
                ChannelOrderPayload payload = read(record.getPayload());
                List<OrderLineRequest> lines = new ArrayList<>();
                for (ChannelOrderPayload.Item item : payload.items()) {
                    ChannelItemMapping mapping = mappings.findByChannelAndExternalId(record.getChannel(), item.id())
                            .orElseThrow(() -> new BusinessRuleException("CHANNEL_ITEM_UNMAPPED",
                                    "\"%s\" is not mapped to a dish".formatted(item.name()), Map.of("name", item.name())));
                    lines.add(new OrderLineRequest(mapping.getMenuItemId(), item.quantity(), blankToNull(item.note()),
                            options(menu.get(mapping.getMenuItemId()), item)));
                }
                Channel channel = record.getChannel();
                OrderResponse order = orderService.placeForChannel(new PlaceOrderRequest(OrderType.TAKEAWAY,
                        customerName(channel, payload), phone(payload), null, null, note(channel, payload),
                        PaymentMethod.ONLINE, null, lines), channel.name(), payload.id());
                payments.settleByPlatform(order.id(), channel.name(), payload.id());
                // what the guest paid the platform (tax included) against what our menu charges for the same
                boolean differs = order.pricing().total().compareTo(payload.total()) != 0;
                record.placed(order.id(), order.pricing().total(), differs, clock.instant());
            });
            ChannelOrder placed = orders.findById(id).orElseThrow();
            audit.received(placed.getChannel(), placed.getExternalId(), placed.isPriceDiffers() ? "PLACED_PRICE_DIFFERS" : "PLACED");
        } catch (DomainException problem) {
            tx.executeWithoutResult(status -> {
                ChannelOrder record = orders.findById(id).orElseThrow();
                record.needsAttention(problem.getCode(), problem.getMessage(), clock.instant());
                audit.received(record.getChannel(), record.getExternalId(), "ATTENTION " + problem.getCode());
            });
        }
    }

    /** Choice ids for "Group: Choice" names, matched without regard to case. Nothing unknown is guessed. */
    private static List<Long> options(MenuItemResponse dish, ChannelOrderPayload.Item item) {
        List<Long> ids = new ArrayList<>();
        for (String option : item.options() == null ? List.<String>of() : item.options()) {
            int colon = option.indexOf(':');
            String group = colon < 0 ? "" : option.substring(0, colon).strip();
            String choice = (colon < 0 ? option : option.substring(colon + 1)).strip();
            Long found = dish.optionGroups().stream()
                    .filter(g -> group.isEmpty() || g.name().equalsIgnoreCase(group))
                    .flatMap(g -> g.choices().stream())
                    .filter(c -> c.name().equalsIgnoreCase(choice))
                    .map(OptionGroupResponse.Choice::id)
                    .findFirst()
                    .orElseThrow(() -> new BusinessRuleException("CHANNEL_OPTION_UNMAPPED",
                            "%s has no option \"%s\"".formatted(dish.name(), option), Map.of("name", dish.name(), "option", option)));
            ids.add(found);
        }
        return ids;
    }

    // ------------------------------------------------------------------------------------------- outbound

    /** The kitchen's progress goes back to the platform, after the change is committed. */
    @TransactionalEventListener
    void onOrderChanged(OrderChangedEvent event) {
        if (event.kind() != OrderChangedEvent.Kind.STATUS_CHANGED) {
            return;
        }
        ChannelStatus status = switch (event.status()) {
            case PREPARING -> ChannelStatus.ACCEPTED;
            case READY -> ChannelStatus.READY;
            case COMPLETED -> ChannelStatus.PICKED_UP;
            case CANCELLED -> ChannelStatus.CANCELLED;
            default -> null;
        };
        if (status == null) {
            return;
        }
        newTx.execute(tx -> orders.findByOrderId(event.orderId())).ifPresent(record -> push(record.getId(), status));
    }

    /** A dish sold out or came back: every platform it is mapped on is told. */
    @TransactionalEventListener
    void onAvailabilityChanged(MenuAvailabilityChangedEvent event) {
        newTx.executeWithoutResult(status -> {
            for (Long itemId : event.menuItemIds()) {
                boolean available = menu.get(itemId).available();
                for (ChannelItemMapping mapping : mappings.findByMenuItemId(itemId)) {
                    ChannelAdapter adapter = gateway.adapter(mapping.getChannel());
                    if (adapter == null) {
                        continue;
                    }
                    try {
                        adapter.setAvailability(mapping.getChannel(), mapping.getExternalId(), available);
                        audit.sent(mapping.getChannel(), "availability", mapping.getExternalId(), available ? "ON" : "OFF");
                    } catch (DomainException ex) {
                        audit.sent(mapping.getChannel(), "availability", mapping.getExternalId(), "FAILED " + ex.getCode());
                    }
                }
            }
        });
    }

    private void push(Long channelOrderId, ChannelStatus status) {
        ChannelOrder record = newTx.execute(tx -> orders.findById(channelOrderId).orElseThrow());
        ChannelAdapter adapter = gateway.adapter(record.getChannel());
        String error = null;
        if (adapter != null) {
            try {
                adapter.updateStatus(record.getChannel(), record.getExternalId(), status);
            } catch (DomainException ex) {
                error = ex.getMessage();
            }
        }
        audit.sent(record.getChannel(), "status " + status, record.getExternalId(), error == null ? "OK" : "FAILED");
        String failure = error;
        newTx.executeWithoutResult(tx -> orders.findById(channelOrderId).orElseThrow().synced(status.name(), failure, clock.instant()));
    }

    // ------------------------------------------------------------------------------------------- managing

    public List<ChannelResponse> channels() {
        return tx.execute(status -> Arrays.stream(Channel.values()).map(channel -> new ChannelResponse(channel,
                channel.label(), gateway.mode(channel), orders.countByChannelAndStatus(channel, ChannelOrder.Status.ATTENTION),
                mappings.findByChannelOrderByExternalNameAsc(channel).size(),
                gateway.recentUpdates(channel).stream()
                        .map(u -> new ChannelResponse.Update(u.what(), u.target(), u.value(), u.at())).toList())).toList());
    }

    public List<ChannelOrderResponse> recentOrders() {
        return tx.execute(status -> orders.findAllByOrderByReceivedAtDescIdDesc(PageRequest.of(0, LIST_LIMIT)).stream()
                .map(this::response).toList());
    }

    public List<MappingResponse> mappings(Channel channel) {
        return tx.execute(status -> mappings.findByChannelOrderByExternalNameAsc(channel).stream().map(this::response).toList());
    }

    /** Maps a platform item to one of our dishes (or moves it to another). */
    public MappingResponse map(Channel channel, String externalId, MappingRequest request) {
        if (externalId.isBlank() || externalId.length() > 80) {
            throw new BusinessRuleException("CHANNEL_ITEM_ID_INVALID", "A platform item id has 1 to 80 characters");
        }
        return tx.execute(status -> {
            menu.get(request.menuItemId());
            ChannelItemMapping mapping = mappings.findByChannelAndExternalId(channel, externalId)
                    .orElseGet(() -> new ChannelItemMapping(channel, externalId, request.externalName().strip(), request.menuItemId()));
            mapping.update(request.externalName().strip(), request.menuItemId());
            return response(mappings.save(mapping));
        });
    }

    public void unmap(Channel channel, String externalId) {
        tx.executeWithoutResult(status -> mappings.findByChannelAndExternalId(channel, externalId).ifPresent(mappings::delete));
    }

    /**
     * Demo: a made-up order from mapped dishes at our prices, as if the platform had sent it.
     *
     * @throws InvalidStateException {@code CHANNEL_NOT_SIMULATED} unless the platform is a simulation
     */
    public ChannelOrderResponse simulate(Channel channel, boolean withUnmappedItem) {
        if (gateway.mode(channel) != ChannelMode.SIMULATED) {
            throw new InvalidStateException("CHANNEL_NOT_SIMULATED", "Only a simulated platform can make up orders",
                    Map.of("channel", channel.name()));
        }
        List<ChannelItemMapping> mapped = tx.execute(status -> mappings.findByChannelOrderByExternalNameAsc(channel)).stream()
                .filter(mapping -> menu.get(mapping.getMenuItemId()).available()).toList();
        if (mapped.isEmpty() && !withUnmappedItem) {
            throw new BusinessRuleException("CHANNEL_NOTHING_MAPPED", "Map at least one dish first");
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<ChannelOrderPayload.Item> items = new ArrayList<>();
        List<ChannelItemMapping> pool = new ArrayList<>(mapped);
        Collections.shuffle(pool);
        for (ChannelItemMapping mapping : pool.subList(0, Math.min(pool.size(), 1 + random.nextInt(2)))) {
            items.add(new ChannelOrderPayload.Item(mapping.getExternalId(), mapping.getExternalName(), 1 + random.nextInt(2),
                    menu.get(mapping.getMenuItemId()).price(), List.of(), null));
        }
        if (withUnmappedItem) {
            items.add(new ChannelOrderPayload.Item("NEW-" + random.nextInt(100, 999), "Günün çorbası", 1,
                    new BigDecimal("150.00"), List.of(), null));
        }
        // the platform charges what our menu does, tax included; an unmapped item adds its own price on top
        List<OrderLineRequest> known = items.stream().filter(i -> !i.id().startsWith("NEW-"))
                .map(i -> new OrderLineRequest(mapped.stream().filter(m -> m.getExternalId().equals(i.id())).findFirst()
                        .orElseThrow().getMenuItemId(), i.quantity(), null, List.of())).toList();
        BigDecimal total = (known.isEmpty() ? BigDecimal.ZERO
                : orderService.quote(new QuoteRequest(OrderType.TAKEAWAY, null, known)).pricing().total())
                .add(withUnmappedItem ? new BigDecimal("165.00") : BigDecimal.ZERO);
        String[] guests = {"Deniz K.", "Ayşe Y.", "Mert A.", "Selin T.", "Can B."};
        ChannelOrderPayload payload = new ChannelOrderPayload("SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                new ChannelOrderPayload.Customer(guests[random.nextInt(guests.length)], null, null), items, total, null);
        return ingest(channel, payload);
    }

    // ------------------------------------------------------------------------------------------- helpers

    private ChannelOrderResponse response(ChannelOrder record) {
        ChannelOrderPayload payload = read(record.getPayload());
        List<ChannelOrderResponse.Unmapped> unmapped = record.getStatus() == ChannelOrder.Status.ATTENTION
                ? payload.items().stream()
                        .filter(item -> mappings.findByChannelAndExternalId(record.getChannel(), item.id()).isEmpty())
                        .map(item -> new ChannelOrderResponse.Unmapped(item.id(), item.name()))
                        .distinct()
                        .toList()
                : List.of();
        return new ChannelOrderResponse(record.getId(), record.getChannel(), record.getExternalId(), record.getStatus(),
                record.getOrderId(), payload.customer().name(), payload.items().stream().mapToInt(ChannelOrderPayload.Item::quantity).sum(),
                record.getPlatformTotal(), record.getOurTotal(), record.isPriceDiffers(), record.getProblemCode(),
                record.getProblem(), unmapped, record.getSyncedStatus(), record.getSyncError(), record.getReceivedAt());
    }

    private MappingResponse response(ChannelItemMapping mapping) {
        return new MappingResponse(mapping.getExternalId(), mapping.getExternalName(), mapping.getMenuItemId(),
                menu.get(mapping.getMenuItemId()).name());
    }

    private ChannelOrder find(Long id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("channel_order", id));
    }

    private static ChannelOrderPayload parse(byte[] body) {
        try {
            return JSON.readValue(body, ChannelOrderPayload.class);
        } catch (JacksonException ex) {
            throw new BusinessRuleException("WEBHOOK_PAYLOAD_INVALID", "The order could not be read");
        }
    }

    private static ChannelOrderPayload read(String payload) {
        return JSON.readValue(payload, ChannelOrderPayload.class);
    }

    private static String write(ChannelOrderPayload payload) {
        return JSON.writeValueAsString(payload);
    }

    private void validate(ChannelOrderPayload payload) {
        Set<ConstraintViolation<ChannelOrderPayload>> violations = validator.validate(payload);
        if (!violations.isEmpty()) {
            ConstraintViolation<ChannelOrderPayload> first = violations.stream()
                    .min(Comparator.comparing(v -> v.getPropertyPath().toString())).orElseThrow();
            throw new BusinessRuleException("WEBHOOK_PAYLOAD_INVALID", "%s %s".formatted(first.getPropertyPath(), first.getMessage()));
        }
    }

    private static String customerName(Channel channel, ChannelOrderPayload payload) {
        String name = channel.label() + " · " + payload.customer().name().strip();
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    /** Platforms often mask the number; a masked one is left out rather than stored half. */
    private static @Nullable String phone(ChannelOrderPayload payload) {
        String phone = payload.customer().phone();
        return phone != null && PHONE.matcher(phone.strip()).matches() ? phone.strip() : null;
    }

    private static String note(Channel channel, ChannelOrderPayload payload) {
        String note = "%s #%s%s".formatted(channel.label(), payload.id(), payload.note() == null || payload.note().isBlank()
                ? "" : " · " + payload.note().strip());
        return note.length() > 255 ? note.substring(0, 255) : note;
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
