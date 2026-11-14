package com.umutvarol.restaurant.billing;

import com.umutvarol.restaurant.business.BillingInterval;
import com.umutvarol.restaurant.billing.BillingProvider.SubscriptionUpdate;
import com.umutvarol.restaurant.business.Branch;
import com.umutvarol.restaurant.business.BranchContext;
import com.umutvarol.restaurant.business.BranchRepository;
import com.umutvarol.restaurant.business.Business;
import com.umutvarol.restaurant.business.Business.Plan;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessProfile;
import com.umutvarol.restaurant.business.BusinessRepository;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.security.AppUserRepository;
import com.umutvarol.restaurant.table.DiningTableRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A business's subscription: what it has, what it uses, paying for a plan and keeping the plan in step with what the
 * provider says. Unpaid businesses are never deleted: a trial that ends or a payment late beyond the grace period
 * makes the business read-only until it pays.
 */
@Service
public class SubscriptionService {

    private static final Logger audit = LoggerFactory.getLogger("billing.audit");

    public record Usage(int openBranches, int activeStaff, int tables) {
    }

    public record Summary(Business business, Usage usage, PlanCatalog.Offer offer, boolean writable, boolean billingEnabled,
                          boolean portal) {
    }

    private final BusinessRepository businesses;
    private final BusinessProfile profile;
    private final BranchRepository branches;
    private final AppUserRepository users;
    private final DiningTableRepository tables;
    private final Optional<BillingProvider> provider;
    private final BillingProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Map<Long, Writable> writable = new ConcurrentHashMap<>();

    private record Writable(boolean writable, Instant until) {
    }

    private static final Duration WRITABLE_TTL = Duration.ofSeconds(30);

    SubscriptionService(BusinessRepository businesses, BusinessProfile profile, BranchRepository branches, AppUserRepository users,
                        DiningTableRepository tables, Optional<BillingProvider> provider, BillingProperties properties,
                        PlatformTransactionManager transactions, Clock clock) {
        this.businesses = businesses;
        this.profile = profile;
        this.branches = branches;
        this.users = users;
        this.tables = tables;
        this.provider = provider;
        this.properties = properties;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** This business's plan, status and usage, counted across all its branches. */
    public Summary summary() {
        Business business = current();
        Usage usage = usage();
        return new Summary(business, usage, PlanCatalog.of(business.getPlan()), business.writable(clock.instant()),
                provider.isPresent(), provider.isPresent() && business.getBillingCustomerRef() != null);
    }

    public Usage usage() {
        return BranchContext.acrossBranches(() -> tx.execute(status -> new Usage(
                (int) branches.findAllByOrderByIdAsc().stream().filter(Branch::isActive).count(),
                (int) users.countByEnabledTrue(),
                tables.findByActiveTrueOrderByIdAsc().size())));
    }

    /** Where the owner pays for {@code plan}: the provider's checkout page. */
    public String checkout(Plan plan, BillingInterval interval, String returnUrl, String actor) {
        BillingProvider billing = provider.orElseThrow(SubscriptionService::off);
        Business business = current();
        Usage usage = usage();
        PlanCatalog.Limits limits = PlanCatalog.of(plan).limits();
        if (exceeds(usage.openBranches(), limits.branches()) || exceeds(usage.activeStaff(), limits.activeStaff())
                || exceeds(usage.tables(), limits.tables())) {
            throw new BusinessRuleException("PLAN_TOO_SMALL", "This business uses more than the " + plan + " plan includes",
                    Map.of("plan", plan.name()));
        }
        int billed = PlanCatalog.billedBranches(plan, usage.openBranches());
        audit.info("billing.checkout business={} plan={} interval={} branches={} by={}", business.getId(), plan, interval, billed, actor);
        String url = billing.checkout(new BillingProvider.CheckoutRequest(business.getId(), business.getName(),
                Optional.ofNullable(business.getOwnerEmail()).orElse(""), plan, interval, billed,
                business.getBillingCustomerRef(), returnUrl));
        if (SimulatedBillingProvider.NAME.equals(billing.name())) {
            apply(billing.name(), new SubscriptionUpdate(business.getId(), "sim-" + business.getId(), "sim-sub-" + business.getId(),
                    plan, interval, billed, SubscriptionUpdate.State.ACTIVE, clock.instant().plus(Duration.ofDays(
                    interval == BillingInterval.YEARLY ? 365 : 30)), clock.instant()));
        }
        return url;
    }

    /** The provider's page for the card, invoices and cancelling. */
    public String portal() {
        BillingProvider billing = provider.orElseThrow(SubscriptionService::off);
        String customer = current().getBillingCustomerRef();
        if (customer == null) {
            throw new BusinessRuleException("NO_SUBSCRIPTION", "This business has no subscription yet");
        }
        return billing.portal(customer).orElseThrow(() -> new BusinessRuleException("NO_PORTAL",
                "Your subscription is managed by the provider's e-mails"));
    }

    /** A webhook from the provider; its signature is checked by the provider. */
    public void webhook(String providerName, byte[] body, Map<String, String> headers) {
        BillingProvider billing = provider.filter(p -> p.name().equalsIgnoreCase(providerName))
                .orElseThrow(() -> new ResourceNotFoundException("billing_provider", providerName));
        billing.parseWebhook(body, headers).ifPresent(update -> apply(billing.name(), update));
    }

    void apply(String providerName, SubscriptionUpdate update) {
        BusinessContext.acrossAll(() -> tx.execute(status -> {
            Business business = businesses.findById(update.businessId())
                    .orElseThrow(() -> new ResourceNotFoundException("business", update.businessId()));
            boolean applied = business.subscribed(providerName, update.customerRef(), update.subscriptionRef(), update.plan(), update.interval(),
                    update.branches(), switch (update.state()) {
                        case ACTIVE -> Business.Status.ACTIVE;
                        case PAST_DUE -> Business.Status.PAST_DUE;
                        case CANCELLED -> Business.Status.CANCELLED;
                    }, update.periodEndsAt(), update.occurredAt(), clock.instant());
            if (!applied) {
                audit.info("billing.subscription_stale business={} occurred={}", business.getId(), update.occurredAt());
                return null;
            }
            audit.info("billing.subscription business={} plan={} interval={} branches={} state={} status={}",
                    business.getId(), update.plan(), update.interval(), update.branches(), update.state(), business.getStatus());
            return null;
        }));
        writable.remove(update.businessId());
        profile.forget(update.businessId());
    }

    /** Branches opened or closed: the provider bills the new number from now on. A failure is retried next time. */
    public void branchesChanged() {
        provider.ifPresent(billing -> {
            Business business = current();
            if (business.getBillingSubscriptionRef() == null || business.getStatus() == Business.Status.CANCELLED) {
                return;
            }
            int billed = PlanCatalog.billedBranches(business.getPlan(), usage().openBranches());
            if (business.getBilledBranches() != null && business.getBilledBranches() == billed) {
                return;
            }
            try {
                billing.changeBranches(business.getBillingSubscriptionRef(), business.getPlan(),
                        business.getBillingInterval(), billed);
                audit.info("billing.branches business={} branches={}", business.getId(), billed);
            } catch (BillingUnavailableException ex) {
                audit.warn("billing.branches_failed business={} branches={} reason={}", business.getId(), billed, ex.getMessage());
            }
        });
    }

    /** Ends trials that ran out and payments late beyond the grace period: those businesses become read-only. */
    public int lapse() {
        Instant now = clock.instant();
        Duration grace = Duration.ofDays(properties.graceDays());
        return BusinessContext.acrossAll(() -> tx.execute(status -> {
            int changed = 0;
            for (Business business : businesses.findByStatusNotOrderByIdAsc(Business.Status.CANCELLED)) {
                if (business.lapse(now, grace)) {
                    audit.info("billing.lapsed business={} status={}", business.getId(), business.getStatus());
                    writable.remove(business.getId());
                    changed++;
                }
            }
            return changed;
        }));
    }

    /**
     * Whether this business may change anything (take orders, add staff); reading and paying always work. Asked on
     * every change, so the answer is remembered for a short while; a webhook or a lapse forgets it at once.
     */
    public boolean writable(long businessId) {
        Instant now = clock.instant();
        Writable known = writable.get(businessId);
        if (known != null && known.until().isAfter(now)) {
            return known.writable();
        }
        boolean answer = BusinessContext.acrossAll(() -> businesses.findById(businessId)
                .map(business -> business.writable(now)).orElse(false));
        writable.put(businessId, new Writable(answer, now.plus(WRITABLE_TTL)));
        return answer;
    }

    /** The signed-in business. */
    public Business business() {
        return current();
    }

    private Business current() {
        long id = Optional.ofNullable(BusinessContext.current()).orElse(1L);
        return BusinessContext.acrossAll(() -> businesses.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("business", id)));
    }

    private static boolean exceeds(int used, int limit) {
        return limit != PlanCatalog.UNLIMITED && used > limit;
    }

    private static BusinessRuleException off() {
        return new BusinessRuleException("BILLING_OFF", "This server does not sell subscriptions");
    }
}
