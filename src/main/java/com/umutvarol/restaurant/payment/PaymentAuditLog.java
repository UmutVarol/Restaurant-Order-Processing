package com.umutvarol.restaurant.payment;

import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * An audit trail of money movements and of anything suspicious around them, written to the {@code payment.audit}
 * logger (route it to tamper-evident storage in production) and counted in Micrometer, so a dashboard or alert
 * can catch, say, a burst of refunds or of failed provider signatures.
 * <p>
 * Values that come from outside are stripped of control characters and shortened, so they cannot forge log lines.
 * Card data never reaches Sofra, so it can never be logged.
 */
@Component
public class PaymentAuditLog {

    private static final Logger audit = LoggerFactory.getLogger("payment.audit");

    private final MeterRegistry meters;

    public PaymentAuditLog(MeterRegistry meters) {
        this.meters = meters;
    }

    void paymentTaken(Payment payment, String actor) {
        audit.info("payment.taken id={} bill={} method={} amount={} tip={} by={}", payment.getId(),
                payment.getBill().getId(), payment.getMethod(), payment.getAmount(), payment.getTip(), clean(actor));
        meters.counter("sofra.payments", "method", payment.getMethod().name(), "outcome", "succeeded").increment();
    }

    void onlinePaymentSettled(Payment payment) {
        audit.info("payment.online id={} bill={} status={} amount={} tip={} review={}", payment.getId(),
                payment.getBill().getId(), payment.getStatus(), payment.getAmount(), payment.getTip(), payment.isUnderReview());
        meters.counter("sofra.payments", "method", "ONLINE", "outcome", payment.getStatus().name().toLowerCase()).increment();
    }

    void refund(Refund refund, String actor) {
        audit.warn("refund id={} payment={} amount={} status={} by={} reason=\"{}\"", refund.getId(),
                refund.getPayment().getId(), refund.getAmount(), refund.getStatus(), clean(actor), clean(refund.getReason()));
        meters.counter("sofra.refunds", "method", refund.getPayment().getMethod().name()).increment();
    }

    void linkCreated(PaymentLink link, String actor) {
        audit.info("link.created id={} bill={} share={} by={}", link.getId(), link.getBill().getId(), link.getAmount(), clean(actor));
    }

    void released(Payment payment, String actor) {
        audit.warn("payment.released id={} bill={} amount={} by={}", payment.getId(), payment.getBill().getId(),
                payment.getAmount(), clean(actor));
    }

    void dayClosed(String date, BigDecimal expected, BigDecimal counted, String actor) {
        audit.warn("day.closed date={} expected={} counted={} variance={} by={}", date, expected, counted,
                counted.subtract(expected), clean(actor));
    }

    /** Something that should not happen in normal use: a forged callback, a bad signature, an amount mismatch. */
    void suspicious(String reason, @Nullable Object detail) {
        audit.warn("suspicious reason={} detail=\"{}\"", reason, clean(String.valueOf(detail)));
        meters.counter("sofra.payments.suspicious", "reason", reason).increment();
    }

    static String clean(@Nullable String value) {
        if (value == null) {
            return "";
        }
        String stripped = value.replaceAll("\\p{Cntrl}", "_");
        return stripped.length() > 120 ? stripped.substring(0, 120) + "…" : stripped;
    }
}
