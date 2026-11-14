package com.umutvarol.restaurant.payment;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessLocator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A guest paying by link belongs to the business of the link; the payment provider's return to the business of the
 * payment it reports on. (The provider's webhook names the business in its address, set up per merchant.)
 */
@Component
class PaymentTokenBusinessLocator implements BusinessLocator {

    private static final Pattern PAY = Pattern.compile("^/api/v1/public/pay/([A-Za-z0-9_-]{6,100})(/.*)?$");

    private final PaymentLinkRepository links;
    private final PaymentRepository payments;

    PaymentTokenBusinessLocator(PaymentLinkRepository links, PaymentRepository payments) {
        this.links = links;
        this.payments = payments;
    }

    @Override
    public Optional<Long> locate(HttpServletRequest request) {
        String path = request.getRequestURI();
        Matcher pay = PAY.matcher(path);
        if (pay.matches()) {
            String hash = OnlinePaymentService.hash(pay.group(1));
            return BusinessContext.acrossAll(() -> links.findByTokenHash(hash).map(PaymentLink::getBusinessId));
        }
        if (path.startsWith("/api/v1/payments/callback/")) {
            String token = request.getParameter("token");
            if (token != null && token.length() <= 200) {
                return BusinessContext.acrossAll(() -> payments.findByProviderToken(token).map(Payment::getBusinessId));
            }
        }
        return Optional.empty();
    }
}
