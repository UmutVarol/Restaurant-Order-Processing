package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.common.config.RestaurantProperties;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.common.error.UpstreamServiceException;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderStatus;
import com.umutvarol.restaurant.payment.dto.CheckoutResponse;
import com.umutvarol.restaurant.payment.dto.PaymentLinkResponse;
import com.umutvarol.restaurant.payment.dto.PublicBillResponse;
import com.umutvarol.restaurant.payment.dto.PublicBillResponse.LastPayment;
import com.umutvarol.restaurant.payment.dto.PublicBillResponse.Line;
import com.umutvarol.restaurant.payment.dto.PublicCheckoutRequest;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.CheckoutOutcome;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.CheckoutRequest;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.CheckoutSession;
import com.umutvarol.restaurant.payment.gateway.OnlinePaymentGateway.RefundOutcome;
import com.umutvarol.restaurant.payment.gateway.PaymentGatewayException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Guests paying from their own phone: the waiter shows a QR code (a payment link), the guest opens it, picks
 * how much to pay and a tip, and is sent to the provider's hosted page for card entry and 3-D Secure.
 * <p>
 * How a payment ended is only ever learned from the provider, server to server; the guest's browser coming back
 * to the callback is just the cue to ask. Calls to the provider are made outside database transactions, so a
 * slow provider never holds a lock on the bill.
 */
@Service
public class OnlinePaymentService {

    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,100}");
    /** The guest's browser comes back here after the provider's page; the page then finds the link it came from. */
    static final String RETURN_PATH = "/#/pay/return";
    /** A second checkout on the same link within this time is refused, so a script cannot spin up holds. */
    static final Duration CHECKOUT_COOLDOWN = Duration.ofSeconds(60);
    /** How soon an unfinished checkout is first checked with the provider, in case the guest never came back. */
    static final Duration RECONCILE_AFTER = Duration.ofMinutes(2);
    private static final Pattern CONVERSATION_ID = Pattern.compile("sofra-p(\\d{1,18})");
    private static final Logger log = LoggerFactory.getLogger(OnlinePaymentService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final BillService billService;
    private final PaymentService paymentService;
    private final PaymentRepository payments;
    private final PaymentLinkRepository links;
    private final RefundRepository refunds;
    private final OnlinePayments online;
    private final PaymentProperties properties;
    private final RestaurantProperties restaurant;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final PaymentAuditLog audit;
    private final Clock clock;

    public OnlinePaymentService(BillService billService, PaymentService paymentService, PaymentRepository payments,
                                PaymentLinkRepository links, RefundRepository refunds, OnlinePayments online,
                                PaymentProperties properties, RestaurantProperties restaurant,
                                ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                                PaymentAuditLog audit, Clock clock) {
        this.billService = billService;
        this.paymentService = paymentService;
        this.payments = payments;
        this.links = links;
        this.refunds = refunds;
        this.online = online;
        this.properties = properties;
        this.restaurant = restaurant;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        // Each step commits on its own, also when called from an after-commit hook.
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------- staff

    /**
     * Creates a payment link for a bill, shown to the guest as a QR code.
     *
     * @param amount  a fixed share for one guest, or {@code null} for whatever is due when the guest pays
     * @param baseUrl the address guests reach the app on
     */
    @Transactional
    public PaymentLinkResponse createLink(Long billId, @Nullable BigDecimal amount, String baseUrl, String actor) {
        online.require();
        Bill bill = billService.lock(billId);
        if (bill.getStatus() != BillStatus.OPEN) {
            throw new InvalidStateException("BILL_NOT_OPEN", "Bill #%d is %s".formatted(billId, bill.getStatus()),
                    Map.of("billId", String.valueOf(billId), "status", bill.getStatus()));
        }
        if (bill.available().signum() <= 0) {
            throw new BusinessRuleException("BILL_ALREADY_PAID", "Nothing is left to pay on bill #%d".formatted(billId));
        }
        BigDecimal share = amount == null ? null : Money.normalize(amount);
        if (share != null && share.compareTo(bill.available()) > 0) {
            throw new BusinessRuleException("PAYMENT_EXCEEDS_BALANCE",
                    "Only %s is still due on bill #%d".formatted(bill.available(), billId),
                    Map.of("available", bill.available()));
        }
        String token = newToken();
        Instant now = clock.instant();
        PaymentLink link = links.save(new PaymentLink(bill, hash(token), share, now.plus(properties.linkTtl()), now, actor));
        audit.linkCreated(link, actor);
        return new PaymentLinkResponse(token, baseUrl + "/#/pay/" + token, share, link.getExpiresAt());
    }

    // ------------------------------------------------------------------------------------------- guests

    @Transactional(readOnly = true)
    public PublicBillResponse view(String token) {
        PaymentLink link = findLink(token);
        Bill bill = link.getBill();
        Instant now = clock.instant();
        List<Order> orders = bill.getOrders().stream().filter(order -> order.getStatus() != OrderStatus.CANCELLED).toList();
        Map<String, Line> lines = new LinkedHashMap<>();
        orders.stream().flatMap(order -> order.getLines().stream()).forEach(line -> lines.merge(
                line.getItemName() + '|' + line.optionSummary(),
                new Line(line.getItemName(), line.getItemIcon(), line.optionSummary(), line.getQuantity(), line.getLineTotal()),
                (a, b) -> new Line(a.name(), a.icon(), a.options(), a.quantity() + b.quantity(),
                        a.lineTotal().add(b.lineTotal()))));

        BigDecimal due = bill.available();
        String reason = !online.enabled() ? "DISABLED"
                : bill.getStatus() != BillStatus.OPEN || due.signum() <= 0 ? "PAID"
                : !link.isUsable(now) ? "EXPIRED"
                : null;
        LastPayment last = bill.getPayments().stream()
                .filter(payment -> payment.getLink() != null && payment.getLink().getId().equals(link.getId()))
                .max(Comparator.comparing(Payment::getId))
                .map(payment -> new LastPayment(payment.getStatus(), payment.getAmount(), payment.getTip(),
                        payment.getFailureReason(), Objects.requireNonNullElse(payment.getCompletedAt(), payment.getCreatedAt())))
                .orElse(null);
        return new PublicBillResponse(
                restaurant.name(),
                bill.getTable() == null ? null : bill.getTable().getCode(),
                List.copyOf(lines.values()),
                bill.total(),
                bill.paid(),
                due,
                link.getAmount() == null ? null : link.getAmount().min(due),
                reason == null,
                reason,
                link.getExpiresAt(),
                online.gateway().map(OnlinePaymentGateway::provider).orElse(null),
                last);
    }

    /**
     * Reserves the amount on the bill and opens a checkout with the provider.
     *
     * @param baseUrl  the address guests reach the app on; the provider sends the guest back there
     * @param clientIp the guest's address, which the provider uses for fraud checks
     */
    public CheckoutResponse checkout(String token, PublicCheckoutRequest request, String baseUrl, String clientIp) {
        OnlinePaymentGateway gateway = online.require();
        // An earlier attempt on this link may have been paid, or abandoned: ask the provider before starting another.
        List<Long> earlier = transaction.execute(status -> payments.findByLinkIdAndStatus(findLink(token).getId(),
                PaymentStatus.PENDING).stream().map(Payment::getId).toList());
        Objects.requireNonNull(earlier).forEach(paymentId -> reconcile(paymentId, false));
        Started started = Objects.requireNonNull(transaction.execute(status -> start(token, request)));

        CheckoutSession session;
        try {
            session = gateway.startCheckout(new CheckoutRequest(
                    conversationId(started.paymentId()),
                    "B" + started.billId(),
                    started.charge(),
                    started.description(),
                    // No link token here: the URL is seen by the provider and may end up in logs.
                    baseUrl + "/api/v1/payments/callback/" + gateway.provider().toLowerCase(),
                    request.name() == null || request.name().isBlank() ? "Sofra Guest" : request.name().strip(),
                    null,
                    clientIp));
        } catch (PaymentGatewayException ex) {
            log.warn("Could not start a checkout for payment {}: {}", started.paymentId(), ex.getMessage());
            transaction.executeWithoutResult(status -> {
                Payment payment = lockedPayment(started.paymentId());
                payment.fail("The payment provider could not be reached.", clock.instant());
                events.publishEvent(PaymentChangedEvent.payment(payment, clock.instant()));
            });
            throw new UpstreamServiceException("PAYMENT_PROVIDER_ERROR",
                    "The payment provider is not responding. Please try again in a moment.");
        }
        transaction.executeWithoutResult(status -> {
            Payment payment = lockedPayment(started.paymentId());
            payment.startedOnline(gateway.provider(), session.token());
            events.publishEvent(PaymentChangedEvent.payment(payment, clock.instant()));
        });
        return new CheckoutResponse(session.paymentPageUrl());
    }

    private Started start(String token, PublicCheckoutRequest request) {
        PaymentLink link = findLink(token);
        Bill bill = billService.lock(link.getBill());
        Instant now = clock.instant();
        if (!link.isUsable(now)) {
            throw new BusinessRuleException(bill.getStatus() == BillStatus.OPEN ? "PAYMENT_LINK_EXPIRED" : "BILL_NOT_OPEN",
                    "This payment link can no longer be used. Ask your waiter for a new one.");
        }
        holdOnePerLink(bill, link, now);
        BigDecimal available = bill.available();
        if (available.signum() <= 0) {
            throw new BusinessRuleException("BILL_ALREADY_PAID", "This bill has already been paid.");
        }
        BigDecimal amount = link.getAmount() != null ? link.getAmount().min(available)
                : request.amount() != null ? request.amount() : available;
        BigDecimal tip = request.tip() == null ? Money.ZERO : request.tip();
        Payment payment = bill.startOnline(amount, tip, link, now, "guest");
        payments.saveAndFlush(payment);
        String description = restaurant.name() + (bill.getTable() == null ? "" : " - " + bill.getTable().getCode());
        return new Started(payment.getId(), bill.getId(), payment.getAmount().add(payment.getTip()), description);
    }

    private record Started(Long paymentId, Long billId, BigDecimal charge, String description) {
    }

    /**
     * A link holds at most one unfinished checkout, so whoever has the QR code cannot tie up more of the bill than
     * one share. A fresh attempt replaces an older one the provider says is unfinished; one started moments ago,
     * or one the provider is reviewing for fraud, is left alone.
     */
    private void holdOnePerLink(Bill bill, PaymentLink link, Instant now) {
        for (Payment pending : bill.getPayments()) {
            if (!pending.isPending() || pending.getLink() == null || !pending.getLink().getId().equals(link.getId())) {
                continue;
            }
            if (pending.isUnderReview() || pending.getCreatedAt().isAfter(now.minus(CHECKOUT_COOLDOWN))) {
                audit.suspicious("checkout_in_progress", "link " + link.getId());
                throw new InvalidStateException("CHECKOUT_IN_PROGRESS",
                        "A payment from this link is already in progress. Finish it or try again in a minute.", Map.of());
            }
            pending.expire(now, "Replaced by a new attempt from the same link.");
            events.publishEvent(PaymentChangedEvent.payment(pending, now));
        }
    }

    /**
     * The guest came back from the provider's page. Whatever the browser says is ignored: the outcome is asked
     * from the provider. Safe to call any number of times.
     */
    public void handleCallback(String provider, String providerToken) {
        Long paymentId = transaction.execute(status -> payments.findByProviderToken(providerToken)
                .filter(payment -> provider.equalsIgnoreCase(payment.getProvider()))
                .map(Payment::getId)
                .orElse(null));
        if (paymentId == null) {
            audit.suspicious("unknown_checkout", provider);
            return;
        }
        reconcile(paymentId, false);
    }

    /**
     * A server-to-server notification from the provider (iyzico webhook). Its body is not trusted either: it only
     * names a checkout, which is then looked up with the provider like a callback. That keeps a forged notification
     * harmless without depending on a signature scheme that must be switched on per merchant account.
     */
    public void handleNotification(String provider, Map<String, Object> body) {
        String token = body.get("token") instanceof String value && value.length() <= 200 ? value : null;
        Long paymentId = transaction.execute(status -> {
            if (token != null) {
                return payments.findByProviderToken(token).map(Payment::getId).orElse(null);
            }
            Object conversation = body.get("paymentConversationId");
            java.util.regex.Matcher match = conversation instanceof String value ? CONVERSATION_ID.matcher(value) : null;
            return match != null && match.matches() ? Long.valueOf(match.group(1)) : null;
        });
        Long found = paymentId == null ? null : transaction.execute(status -> payments.findById(paymentId)
                .filter(payment -> provider.equalsIgnoreCase(payment.getProvider()))
                .map(Payment::getId).orElse(null));
        if (found == null) {
            audit.suspicious("unknown_notification", provider);
            return;
        }
        reconcile(found, false);
    }

    /**
     * Staff give up an online payment that is still open, for instance when the guest decided to pay in cash after
     * all. The provider is asked first; a payment that went through is kept.
     */
    public void release(Long billId, Long paymentId, String actor) {
        Long owner = transaction.execute(status -> payments.findById(paymentId).map(p -> p.getBill().getId()).orElse(null));
        if (owner == null || !owner.equals(billId)) {
            throw new ResourceNotFoundException("payment", paymentId);
        }
        reconcile(paymentId, false);
        transaction.executeWithoutResult(status -> {
            Payment payment = lockedPayment(paymentId);
            if (!payment.isPending()) {
                return; // it finished meanwhile: nothing to release
            }
            if (payment.isUnderReview()) {
                throw new InvalidStateException("PAYMENT_UNDER_REVIEW",
                        "The card was charged and the provider is reviewing it; it cannot be released.", Map.of());
            }
            Instant now = clock.instant();
            payment.expire(now, "Released by staff.");
            audit.released(payment, actor);
            events.publishEvent(PaymentChangedEvent.payment(payment, now));
            billService.refresh(payment.getBill(), now);
        });
    }

    // ------------------------------------------------------------------------------------------- background

    /**
     * Unfinished online payments are checked with the provider after a couple of minutes, so a guest who paid but
     * closed the browser before coming back is still recorded quickly. Payments still unfinished after
     * {@code pending-ttl} are given up, which frees their amount on the bill.
     */
    @Scheduled(fixedDelayString = "${restaurant.payments.reconcile-interval:PT1M}", initialDelayString = "PT1M")
    public void reconcilePendingPayments() {
        Instant now = clock.instant();
        Instant giveUp = now.minus(properties.pendingTtl());
        List<Payment> open = transaction.execute(status ->
                payments.findByStatusAndCreatedAtBefore(PaymentStatus.PENDING, now.minus(RECONCILE_AFTER)));
        for (Payment payment : Objects.requireNonNull(open)) {
            try {
                reconcile(payment.getId(), payment.getCreatedAt().isBefore(giveUp));
            } catch (RuntimeException ex) {
                log.warn("Could not reconcile payment {}: {}", payment.getId(), ex.getMessage());
            }
        }
    }

    /**
     * Asks the provider how a payment ended and records it.
     *
     * @param expireIfUnfinished give the payment up when the provider says it is not finished
     */
    void reconcile(Long paymentId, boolean expireIfUnfinished) {
        Snapshot snapshot = transaction.execute(status -> payments.findById(paymentId)
                .map(payment -> new Snapshot(payment.getStatus(), payment.getProvider(), payment.getProviderToken()))
                .orElse(null));
        if (snapshot == null || (snapshot.status() != PaymentStatus.PENDING && snapshot.status() != PaymentStatus.EXPIRED)) {
            return;
        }
        CheckoutOutcome outcome = CheckoutOutcome.pending();
        OnlinePaymentGateway gateway = online.gateway()
                .filter(active -> active.provider().equals(snapshot.provider()))
                .orElse(null);
        if (gateway != null && snapshot.providerToken() != null) {
            try {
                outcome = gateway.retrieve(snapshot.providerToken(), conversationId(paymentId));
            } catch (PaymentGatewayException ex) {
                log.warn("Could not ask {} about payment {}: {}", snapshot.provider(), paymentId, ex.getMessage());
                if (!(ex.getCause() instanceof org.springframework.web.client.RestClientException)) {
                    // Answered, but the answer failed verification: possibly tampered with.
                    audit.suspicious("unverified_provider_answer", "payment " + paymentId + ": " + ex.getMessage());
                }
                return;
            }
        }
        CheckoutOutcome result = outcome;
        transaction.executeWithoutResult(status -> apply(paymentId, result, expireIfUnfinished));
    }

    private void apply(Long paymentId, CheckoutOutcome outcome, boolean expireIfUnfinished) {
        Payment found = payments.findById(paymentId).orElseThrow();
        Bill bill = billService.lock(found.getBill());
        Payment payment = bill.getPayments().stream().filter(p -> p.getId().equals(paymentId)).findFirst().orElseThrow();
        Instant now = clock.instant();
        boolean open = payment.getStatus() == PaymentStatus.PENDING;
        switch (outcome.status()) {
            case SUCCEEDED -> {
                if (!open && payment.getStatus() != PaymentStatus.EXPIRED) {
                    return;
                }
                BigDecimal expected = payment.getAmount().add(payment.getTip());
                if (outcome.paidAmount() != null && outcome.paidAmount().compareTo(expected) != 0) {
                    log.error("Payment {} was charged {} but {} was expected; check it in the provider panel",
                            paymentId, outcome.paidAmount(), expected);
                    audit.suspicious("amount_mismatch", "payment %d charged %s expected %s".formatted(paymentId,
                            outcome.paidAmount(), expected));
                    if (open) {
                        payment.fail("The amount charged did not match. Staff will check it.", now);
                    }
                } else {
                    payment.succeed(outcome.providerPaymentId(), now);
                    // A late success (after expiry, or a second guest paying the same share) can overpay the bill.
                    billService.announceRefunds(bill.refundOverpayment("Paid more than was due", now, "system"), now);
                }
            }
            case FAILED -> {
                if (open) {
                    payment.fail(Objects.requireNonNullElse(outcome.failureReason(), "The payment was declined."), now);
                }
            }
            case REVIEW -> {
                if (open && !payment.isUnderReview()) {
                    payment.underReview(outcome.providerPaymentId());
                }
            }
            case PENDING -> {
                if (open && expireIfUnfinished && !payment.isUnderReview()) {
                    payment.expire(now);
                }
            }
        }
        if (payment.getStatus() != PaymentStatus.PENDING || payment.isUnderReview()) {
            audit.onlinePaymentSettled(payment);
        }
        events.publishEvent(PaymentChangedEvent.payment(payment, now));
        paymentService.afterPayment(bill, "guest", now);
    }

    private record Snapshot(PaymentStatus status, @Nullable String provider, @Nullable String providerToken) {
    }

    /** Sends an online refund to the provider as soon as the refund is committed. */
    @TransactionalEventListener
    void onRefundRequested(OnlineRefundRequested event) {
        sendRefund(event.refundId());
    }

    /** Refunds the provider could not be reached for are tried again. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    public void retryPendingRefunds() {
        Instant settled = clock.instant().minusSeconds(60);
        List<Long> pending = transaction.execute(status -> refunds.findByStatus(RefundStatus.PENDING).stream()
                .filter(refund -> refund.getCreatedAt().isBefore(settled))
                .map(Refund::getId)
                .toList());
        Objects.requireNonNull(pending).forEach(this::sendRefund);
    }

    void sendRefund(Long refundId) {
        Instant now = clock.instant();
        Integer claimed = transaction.execute(status -> refunds.claim(refundId, now, now.plus(Duration.ofMinutes(2))));
        if (claimed == null || claimed != 1) {
            return; // already done, or being sent by another attempt or server
        }
        RefundOrder order = transaction.execute(status -> refunds.findById(refundId)
                .filter(refund -> refund.getStatus() == RefundStatus.PENDING)
                .map(refund -> new RefundOrder(refund.getPayment().getProvider(),
                        refund.getPayment().getProviderPaymentId(), refund.getAmount(),
                        "sofra-r" + refund.getId()))
                .orElse(null));
        if (order == null) {
            return;
        }
        OnlinePaymentGateway gateway = online.gateway().filter(active -> active.provider().equals(order.provider()))
                .orElse(null);
        if (gateway == null || order.providerPaymentId() == null) {
            log.warn("Refund {} waits: provider {} is not configured", refundId, order.provider());
            return;
        }
        RefundOutcome outcome;
        try {
            outcome = gateway.refund(order.providerPaymentId(), order.amount(), order.conversationId());
        } catch (PaymentGatewayException ex) {
            log.warn("Refund {} could not be sent, will retry: {}", refundId, ex.getMessage());
            return;
        }
        transaction.executeWithoutResult(status -> {
            Refund refund = refunds.findById(refundId).orElseThrow();
            billService.lock(refund.getPayment().getBill());
            if (outcome.succeeded()) {
                refund.succeed(outcome.providerRefundId());
            } else {
                log.error("{} refused refund {}: {}", order.provider(), refundId, outcome.failureReason());
                refund.fail(Objects.requireNonNullElse(outcome.failureReason(), "Refused by the provider"));
            }
            audit.refund(refund, "provider");
            events.publishEvent(PaymentChangedEvent.refund(refund, clock.instant()));
        });
    }

    private record RefundOrder(String provider, @Nullable String providerPaymentId, BigDecimal amount,
                               String conversationId) {
    }

    // ------------------------------------------------------------------------------------------- helpers

    private PaymentLink findLink(String token) {
        if (!TOKEN.matcher(token).matches()) {
            throw new ResourceNotFoundException("payment_link", "?");
        }
        return links.findByTokenHash(hash(token)).orElseThrow(() -> new ResourceNotFoundException("payment_link", "?"));
    }

    private Payment lockedPayment(Long paymentId) {
        Payment found = payments.findById(paymentId).orElseThrow();
        Bill bill = billService.lock(found.getBill());
        return bill.getPayments().stream().filter(p -> p.getId().equals(paymentId)).findFirst().orElseThrow();
    }

    /** Our reference for a payment at the provider; the provider echoes it back and we check it. */
    static String conversationId(Long paymentId) {
        return "sofra-p" + paymentId;
    }

    /** 256 random bits: unguessable, so the link itself is the guest's permission to see and pay the bill. */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
