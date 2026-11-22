package com.umutvarol.restaurant.directory;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderLine;
import com.umutvarol.restaurant.payment.Bill;
import com.umutvarol.restaurant.payment.BillRepository;
import com.umutvarol.restaurant.payment.OnlinePaymentService;
import com.umutvarol.restaurant.payment.PaymentStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verified-visit ratings (round three): a guest who paid a bill may rate that visit once, from the payment page or
 * the guest app; the restaurant reads them, answers them in public, and sees who is in (panel card R1).
 */
@Service
@Transactional
public class RatingService {

    private static final Logger audit = LoggerFactory.getLogger("directory.audit");

    /** What a guest can tick besides the stars. Labels are the app's, in six languages. */
    public static final Set<String> TAGS = Set.of("TASTE", "SPEED", "FRIENDLY", "CLEAN", "VALUE");

    public record GuestRating(boolean allowed, boolean rated, @Nullable Integer stars, List<String> dishes) {
    }

    public record RateRequest(int stars, @Nullable List<String> tags, @Nullable List<String> liked, @Nullable String comment) {
    }

    public record StaffRating(Long id, Long branchId, int stars, List<String> tags, List<String> liked,
                              @Nullable String comment, Instant at, @Nullable String reply, @Nullable Instant repliedAt) {
    }

    /** Panel card R1. {@code returning}: share of this month's guests (by device) seen on more than one day. */
    public record Summary(long insideNow, @Nullable Double weekRating, long weekRatings, long weekGuests,
                          @Nullable Integer returning) {
    }

    private final OnlinePaymentService payments;
    private final BillRepository bills;
    private final VisitRatingRepository ratings;
    private final VenueCheckinRepository checkins;
    private final Clock clock;

    RatingService(OnlinePaymentService payments, BillRepository bills, VisitRatingRepository ratings,
                  VenueCheckinRepository checkins, Clock clock) {
        this.payments = payments;
        this.bills = bills;
        this.ratings = ratings;
        this.checkins = checkins;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------ guests

    @Transactional(readOnly = true)
    public GuestRating forGuest(String token) {
        Bill bill = bill(token);
        var existing = ratings.findByBillId(bill.getId());
        return new GuestRating(paid(bill), existing.isPresent(), existing.map(VisitRating::getStars).orElse(null), dishes(bill));
    }

    public GuestRating rate(String token, RateRequest request) {
        Bill bill = bill(token);
        if (!paid(bill)) {
            throw new BusinessRuleException("RATING_NOT_PAID", "A visit can be rated once its bill is paid");
        }
        if (ratings.findByBillId(bill.getId()).isPresent()) {
            throw new BusinessRuleException("RATING_ALREADY_GIVEN", "This visit has already been rated");
        }
        if (request.stars() < 1 || request.stars() > 5) {
            throw new BusinessRuleException("RATING_STARS_INVALID", "Give one to five stars");
        }
        Set<String> tags = new LinkedHashSet<>();
        for (String tag : request.tags() == null ? List.<String>of() : request.tags()) {
            if (TAGS.contains(tag)) {
                tags.add(tag);
            }
        }
        List<String> menu = dishes(bill);
        Set<String> liked = new LinkedHashSet<>();
        for (String dish : request.liked() == null ? List.<String>of() : request.liked()) {
            if (menu.contains(dish) && liked.size() < 8) {
                liked.add(dish);
            }
        }
        String comment = request.comment() == null || request.comment().isBlank() ? null : request.comment().strip();
        if (comment != null && comment.length() > 500) {
            throw new BusinessRuleException("RATING_COMMENT_TOO_LONG", "Keep the comment under 500 characters");
        }
        Long branch = bill.getBranchId();
        if (branch == null) {
            throw new BusinessRuleException("RATING_NO_BRANCH", "This bill belongs to no branch");
        }
        ratings.save(new VisitRating(branch, bill.getId(), request.stars(), tags.isEmpty() ? null : String.join(",", tags),
                liked.isEmpty() ? null : truncate(String.join("\n", liked), 400), comment, clock.instant()));
        audit.info("directory.rating bill={} stars={}", bill.getId(), request.stars());
        return new GuestRating(true, true, request.stars(), menu);
    }

    // ------------------------------------------------------------------------------------------ restaurant

    @Transactional(readOnly = true)
    public List<StaffRating> recent(int limit) {
        return ratings.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 100)))).stream()
                .map(RatingService::staff).toList();
    }

    public StaffRating reply(Long id, @Nullable String text, String actor) {
        VisitRating rating = ratings.findById(id).orElseThrow(() -> new ResourceNotFoundException("Rating", id));
        String clean = text == null || text.isBlank() ? null : text.strip();
        if (clean != null && clean.length() > 500) {
            throw new BusinessRuleException("RATING_REPLY_TOO_LONG", "Keep the reply under 500 characters");
        }
        rating.answer(clean, actor, clock.instant());
        audit.info("directory.rating_reply rating={} by={}", id, actor);
        return staff(rating);
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        Instant now = clock.instant();
        long inside = checkins.countDevicesSince(now.minus(DirectoryService.INSIDE));
        Object[] week = ratings.summarySince(now.minus(Duration.ofDays(7))).getFirst();
        long weekCount = ((Number) week[0]).longValue();
        Double weekRating = weekCount == 0 ? null : Math.round(((Number) week[1]).doubleValue() * 10) / 10d;
        long weekGuests = checkins.findByCreatedAtGreaterThanEqual(now.minus(Duration.ofDays(7))).stream()
                .map(VenueCheckin::getDeviceHash).distinct().count();
        Map<String, Set<Instant>> days = new HashMap<>();
        for (VenueCheckin checkin : checkins.findByCreatedAtGreaterThanEqual(now.minus(Duration.ofDays(30)))) {
            days.computeIfAbsent(checkin.getDeviceHash(), k -> new java.util.HashSet<>())
                    .add(checkin.getCreatedAt().truncatedTo(ChronoUnit.DAYS));
        }
        Integer returning = days.isEmpty() ? null
                : (int) Math.round(100.0 * days.values().stream().filter(set -> set.size() > 1).count() / days.size());
        return new Summary(inside, weekRating, weekCount, weekGuests, returning);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Bill bill(String token) {
        Long id = payments.billOfLink(token, false);
        return bills.findById(id).orElseThrow(() -> new ResourceNotFoundException("Bill", id));
    }

    private static boolean paid(Bill bill) {
        return bill.getPayments().stream().anyMatch(p -> p.getStatus() == PaymentStatus.SUCCEEDED);
    }

    /** The dishes of the bill, so a guest can say which ones they liked. */
    private static List<String> dishes(Bill bill) {
        Set<String> names = new LinkedHashSet<>();
        for (Order order : bill.getOrders()) {
            for (OrderLine line : order.getLines()) {
                names.add(line.getItemName());
            }
        }
        return List.copyOf(names);
    }

    private static StaffRating staff(VisitRating r) {
        List<String> liked = r.getLiked() == null ? List.of() : List.of(r.getLiked().split("\n"));
        return new StaffRating(r.getId(), r.getBranchId(), r.getStars(), DirectoryService.review(r).tags(), liked,
                r.getComment(), r.getCreatedAt(), r.getReply(), r.getRepliedAt());
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
