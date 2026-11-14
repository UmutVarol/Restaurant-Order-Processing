package com.umutvarol.restaurant.staff;

import com.umutvarol.restaurant.business.WorkingBranch;
import com.umutvarol.restaurant.common.config.RestaurantProperties;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.money.Money;
import com.umutvarol.restaurant.payment.PaymentRepository;
import com.umutvarol.restaurant.security.AppUser;
import com.umutvarol.restaurant.security.Role;
import com.umutvarol.restaurant.staff.dto.ClockStatus;
import com.umutvarol.restaurant.staff.dto.TipPoolResponse;
import com.umutvarol.restaurant.staff.dto.TipPoolResponse.Share;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tip pooling: a day's tips (card, cash and online, as recorded on the payments) are shared among the people who
 * worked that day, in proportion to their minutes on the clock times a weight for their role (by default a waiter
 * 1, the kitchen 0.5, managers 0). The shares always add up to the total to the kuruş: the leftover kuruş go to
 * the largest remainders. Until a manager closes the day it is only a preview; once closed it is fixed, for
 * payroll, and nobody may still be clocked in on it.
 */
@Service
@Transactional
public class TipPoolService {

    private static final Logger audit = LoggerFactory.getLogger("staff.audit");

    private final TipPoolRepository pools;
    private final TimeEntryRepository entries;
    private final PaymentRepository payments;
    private final Clock clock;
    private final WorkingBranch branch;
    private final ZoneId zone;
    private final Map<Role, BigDecimal> weights;

    public TipPoolService(TipPoolRepository pools, TimeEntryRepository entries, PaymentRepository payments, Clock clock,
                          WorkingBranch branch,
                          RestaurantProperties restaurant,
                          @Value("${restaurant.tips.weight.waiter:1.0}") BigDecimal waiter,
                          @Value("${restaurant.tips.weight.kitchen:0.5}") BigDecimal kitchen,
                          @Value("${restaurant.tips.weight.manager:0}") BigDecimal manager) {
        this.pools = pools;
        this.branch = branch;
        this.entries = entries;
        this.payments = payments;
        this.clock = clock;
        this.zone = restaurant.timeZone();
        this.weights = Map.of(Role.WAITER, waiter, Role.KITCHEN, kitchen, Role.ADMIN, manager);
    }

    @Transactional(readOnly = true)
    public TipPoolResponse day(LocalDate date) {
        long here = branch.require();
        return pools.findByBusinessDateAndBranchId(date, here).map(TipPoolService::toResponse).orElseGet(() -> preview(date, here));
    }

    public TipPoolResponse close(LocalDate date, String actor) {
        if (date.isAfter(LocalDate.now(clock.withZone(zone)))) {
            throw new BusinessRuleException("TIP_POOL_FUTURE", "That day has not happened yet");
        }
        long here = branch.require();
        if (pools.findByBusinessDateAndBranchId(date, here).isPresent()) {
            throw new InvalidStateException("TIP_POOL_CLOSED", "The tips of that day are already shared out", Map.of());
        }
        TipPoolResponse preview = preview(date, here);
        if (!preview.stillClockedIn().isEmpty()) {
            throw new InvalidStateException("TIP_POOL_STAFF_CLOCKED_IN",
                    "Still clocked in: " + String.join(", ", preview.stillClockedIn()),
                    Map.of("names", String.join(", ", preview.stillClockedIn())));
        }
        if (preview.tipsTotal().signum() > 0 && preview.shares().isEmpty()) {
            throw new BusinessRuleException("TIP_POOL_NOBODY_WORKED", "Nobody who shares tips was on the clock that day");
        }
        TipPool pool = new TipPool(date, preview.tipsTotal(), actor, clock.instant());
        Map<Long, AppUser> people = workers(date);
        for (Share share : preview.shares()) {
            pool.add(new TipShare(pool, people.get(share.userId()), share.minutes(), share.weight(), share.amount()));
        }
        pools.save(pool);
        audit.info("tips.closed date={} total={} shares={} by={}", date, preview.tipsTotal(), preview.shares().size(), actor);
        return toResponse(pool);
    }

    /** What a person received from closed days in [from, to], newest first. */
    @Transactional(readOnly = true)
    List<ClockStatus.MyTip> sharesOf(Long userId, LocalDate from, LocalDate to) {
        return pools.findByBusinessDateBetweenOrderByBusinessDateDesc(from, to).stream()
                .flatMap(pool -> pool.getShares().stream()
                        .filter(s -> s.getUser().getId().equals(userId))
                        .map(s -> new ClockStatus.MyTip(pool.getBusinessDate(), s.getAmount(), s.getMinutes())))
                .toList();
    }

    /** How a branch's tips of a day would be shared: its tips, among those who clocked in there. */
    private TipPoolResponse preview(LocalDate date, long here) {
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        Instant now = clock.instant();
        BigDecimal total = Money.normalize(payments.sumTips(start, end, here));

        Map<Long, AppUser> people = new LinkedHashMap<>();
        Map<Long, Long> minutes = new LinkedHashMap<>();
        List<String> open = new ArrayList<>();
        for (TimeEntry e : entries.overlapping(start, end).stream().filter(e -> Long.valueOf(here).equals(e.getBranchId())).toList()) {
            AppUser u = e.getUser();
            people.putIfAbsent(u.getId(), u);
            minutes.merge(u.getId(), e.within(start, end, now).toMinutes(), Long::sum);
            if (e.isOpen() && !open.contains(u.getDisplayName())) {
                open.add(u.getDisplayName());
            }
        }
        record Points(AppUser user, int minutes, BigDecimal weight, BigDecimal points) {
        }
        List<Points> sharing = people.values().stream()
                .map(u -> {
                    int m = Math.toIntExact(minutes.get(u.getId()));
                    BigDecimal w = weights.getOrDefault(u.getRole(), BigDecimal.ZERO);
                    return new Points(u, m, w, w.multiply(BigDecimal.valueOf(m)));
                })
                .filter(p -> p.points().signum() > 0)
                .toList();
        BigDecimal totalPoints = sharing.stream().map(Points::points).reduce(BigDecimal.ZERO, BigDecimal::add);

        // largest remainder: whole kuruş by proportion first, the leftover kuruş to the biggest fractions
        long cents = total.movePointRight(2).longValueExact();
        long[] given = new long[sharing.size()];
        BigDecimal[] fraction = new BigDecimal[sharing.size()];
        long handedOut = 0;
        for (int i = 0; i < sharing.size(); i++) {
            BigDecimal exact = totalPoints.signum() == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(cents).multiply(sharing.get(i).points()).divide(totalPoints, 10, RoundingMode.DOWN);
            given[i] = exact.setScale(0, RoundingMode.DOWN).longValue();
            fraction[i] = exact.subtract(BigDecimal.valueOf(given[i]));
            handedOut += given[i];
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < sharing.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.<Integer, BigDecimal>comparing(i -> fraction[i]).reversed()
                .thenComparing(i -> -sharing.get(i).minutes())
                .thenComparing(i -> sharing.get(i).user().getId()));
        for (int k = 0; k < cents - handedOut && !order.isEmpty(); k++) {
            given[order.get(k % order.size())]++;
        }
        List<Share> shares = new ArrayList<>();
        for (int i = 0; i < sharing.size(); i++) {
            Points p = sharing.get(i);
            shares.add(new Share(p.user().getId(), p.user().getDisplayName(), p.user().getRole(), p.minutes(), p.weight(),
                    BigDecimal.valueOf(given[i], 2)));
        }
        shares.sort(Comparator.comparing(Share::amount).reversed().thenComparing(Share::displayName));
        return new TipPoolResponse(date, total, false, null, null, shares, open);
    }

    private Map<Long, AppUser> workers(LocalDate date) {
        Map<Long, AppUser> people = new LinkedHashMap<>();
        entries.overlapping(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
                .forEach(e -> people.putIfAbsent(e.getUser().getId(), e.getUser()));
        return people;
    }

    private static TipPoolResponse toResponse(TipPool pool) {
        List<Share> shares = pool.getShares().stream()
                .map(s -> new Share(s.getUser().getId(), s.getUser().getDisplayName(), s.getUser().getRole(),
                        s.getMinutes(), s.getWeight(), s.getAmount()))
                .sorted(Comparator.comparing(Share::amount).reversed().thenComparing(Share::displayName))
                .toList();
        return new TipPoolResponse(pool.getBusinessDate(), pool.getTipsTotal(), true, pool.getCreatedBy(),
                pool.getCreatedAt(), shares, List.of());
    }
}
