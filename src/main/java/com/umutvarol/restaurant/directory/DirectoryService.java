package com.umutvarol.restaurant.directory;

import com.umutvarol.restaurant.business.Branch;
import com.umutvarol.restaurant.business.BranchRepository;
import com.umutvarol.restaurant.business.Business;
import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessRepository;
import com.umutvarol.restaurant.common.config.RestaurantProperties;
import com.umutvarol.restaurant.common.crypto.SecretTokens;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.guest.BrandingService;
import com.umutvarol.restaurant.menu.MenuService;
import com.umutvarol.restaurant.menu.dto.MenuItemResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The guest app's directory (round three, docs/GUEST_APP_PLAN.md): which listed restaurants are near a guest, a
 * restaurant's public page, and a guest saying "I am here". A position is used for one search and never stored.
 */
@Service
@Transactional
public class DirectoryService {

    /** How far a guest can be from a restaurant and still be offered it, in metres. */
    static final int MIN_RADIUS = 120;
    static final int MAX_RADIUS = 600;
    static final int MAX_RESULTS = 5;
    /** A check-in counts as "inside now" for this long. */
    static final Duration INSIDE = Duration.ofHours(2);

    public record Venue(String business, String branch, String name, @Nullable String branchName,
                        @Nullable String address, @Nullable String accent, boolean logo, int distance,
                        @Nullable Double rating, long ratings, double latitude, double longitude) {
    }

    /** {@code confident}: one place stands out, so the app can greet the guest with it (G2) rather than a list (G3). */
    public record Nearby(List<Venue> venues, boolean confident) {
    }

    public record Review(int stars, List<String> tags, @Nullable String comment, Instant at, @Nullable String reply) {
    }

    public record VenuePage(String business, String branch, String name, @Nullable String branchName,
                            @Nullable String address, @Nullable String accent, boolean logo,
                            @Nullable Double rating, long ratings, List<Review> reviews, List<MenuItemResponse> menu) {
    }

    public record DirectoryEntry(Long branchId, String branch, @Nullable Double latitude, @Nullable Double longitude,
                                 @Nullable String address, boolean listed) {
    }

    private final BranchRepository branches;
    private final BusinessRepository businesses;
    private final VenueCheckinRepository checkins;
    private final VisitRatingRepository ratings;
    private final BrandingService branding;
    private final MenuService menu;
    private final RestaurantProperties restaurant;
    private final Clock clock;

    DirectoryService(BranchRepository branches, BusinessRepository businesses, VenueCheckinRepository checkins,
                     VisitRatingRepository ratings, BrandingService branding, MenuService menu,
                     RestaurantProperties restaurant, Clock clock) {
        this.branches = branches;
        this.businesses = businesses;
        this.checkins = checkins;
        this.ratings = ratings;
        this.branding = branding;
        this.menu = menu;
        this.restaurant = restaurant;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------ guests

    /**
     * Listed restaurants around a position, nearest first. The search radius follows the phone's own accuracy, since
     * a phone indoors may be tens of metres off; the guest always confirms the place.
     */
    // no surrounding transaction: each lookup runs in the business it reads (the directory spans businesses)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public Nearby nearby(double latitude, double longitude, @Nullable Double accuracy) {
        double acc = accuracy == null || accuracy <= 0 ? 50 : Math.min(accuracy, 1000);
        int radius = (int) Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, acc * 2.5));
        double dLat = radius / 111_320d;
        double dLng = radius / (111_320d * Math.max(0.2, Math.cos(Math.toRadians(latitude))));
        List<Branch> found = BusinessContext.acrossAll(() -> branches.findByListedTrueAndActiveTrueAndLatitudeBetweenAndLongitudeBetween(
                latitude - dLat, latitude + dLat, longitude - dLng, longitude + dLng));
        List<Venue> venues = new ArrayList<>();
        for (Branch branch : found) {
            int distance = (int) Math.round(metres(latitude, longitude, branch.getLatitude(), branch.getLongitude()));
            if (distance > radius) {
                continue;
            }
            Optional<Business> owner = BusinessContext.acrossAll(() -> businesses.findById(branch.getBusinessId()));
            if (owner.isEmpty() || owner.get().getStatus() == Business.Status.CANCELLED) {
                continue;
            }
            venues.add(BusinessContext.call(branch.getBusinessId(), () -> venue(owner.get(), branch, distance)));
        }
        venues.sort(Comparator.comparingInt(Venue::distance));
        List<Venue> nearest = venues.subList(0, Math.min(MAX_RESULTS, venues.size()));
        boolean confident = !nearest.isEmpty() && nearest.getFirst().distance() <= Math.max(40, acc)
                && (nearest.size() == 1 || nearest.get(1).distance() - nearest.getFirst().distance() >= Math.max(25, acc / 2));
        return new Nearby(List.copyOf(nearest), confident);
    }

    /** A restaurant's public page in the guest app: who it is, what guests said, and its menu at this branch's prices. */
    @Transactional(readOnly = true)
    public VenuePage page(String branchCode) {
        Branch branch = listed(branchCode);
        Business owner = owner();
        Venue venue = venue(owner, branch, 0);
        List<Review> reviews = ratings.findByBranchIdOrderByCreatedAtDesc(branch.getId(), PageRequest.of(0, 20)).stream()
                .filter(r -> r.getComment() != null || r.getReply() != null)
                .limit(5)
                .map(DirectoryService::review)
                .toList();
        List<MenuItemResponse> items = menu.list(null, null, false, branch.getId());
        return new VenuePage(venue.business(), venue.branch(), venue.name(), venue.branchName(), venue.address(),
                venue.accent(), venue.logo(), venue.rating(), venue.ratings(), reviews, items);
    }

    /** The guest confirmed they are here. Only an anonymous hash of their device key is kept, to count returns. */
    public void checkIn(String branchCode, @Nullable String device, VenueCheckin.Method method) {
        Branch branch = listed(branchCode);
        String key = device == null || device.isBlank() ? "anonymous-" + clock.instant().toEpochMilli() : device.strip();
        String hash = SecretTokens.hash(BusinessContext.current() + ":" + key);
        checkins.save(new VenueCheckin(branch.getId(), hash, method, clock.instant()));
    }

    public Optional<BrandingService.Logo> logo(String branchCode) {
        listed(branchCode);
        return branding.logo();
    }

    // ------------------------------------------------------------------------------------------ restaurant

    @Transactional(readOnly = true)
    public List<DirectoryEntry> entries() {
        return branches.findAllByOrderByIdAsc().stream().filter(Branch::isActive).map(DirectoryService::entry).toList();
    }

    public DirectoryEntry place(Long branchId, @Nullable Double latitude, @Nullable Double longitude,
                                @Nullable String address, boolean listed) {
        Branch branch = branches.findById(branchId).orElseThrow(() -> new ResourceNotFoundException("Branch", branchId));
        if ((latitude == null) != (longitude == null)) {
            throw new BusinessRuleException("DIRECTORY_POSITION_INCOMPLETE", "Give both latitude and longitude, or neither");
        }
        if (listed && latitude == null) {
            throw new BusinessRuleException("DIRECTORY_POSITION_NEEDED", "Set the branch's position before listing it");
        }
        String clean = address == null || address.isBlank() ? null : address.strip();
        branch.placeInDirectory(latitude, longitude, clean, listed);
        return entry(branch);
    }

    // ------------------------------------------------------------------------------------------ helpers

    private Branch listed(String code) {
        return branches.findByCode(code).filter(Branch::isActive).filter(Branch::isListed)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant", code));
    }

    private Business owner() {
        Long id = BusinessContext.current();
        return BusinessContext.acrossAll(() -> businesses.findById(id)).orElseThrow(() -> new ResourceNotFoundException("Restaurant", id));
    }

    private Venue venue(Business owner, Branch branch, int distance) {
        String name = owner.getId() == 1L && "Sofra".equals(owner.getName()) ? restaurant.name() : owner.getName();
        boolean chain = branches.countByActiveTrue() > 1;
        var look = branding.get();
        Object[] summary = ratings.summaryOfBranch(branch.getId()).getFirst();
        long count = ((Number) summary[0]).longValue();
        Double average = count == 0 ? null : Math.round(((Number) summary[1]).doubleValue() * 10) / 10d;
        return new Venue(owner.getSlug(), branch.getCode(), name, chain ? branch.getName() : null, branch.getAddress(),
                look.accent(), look.logoVersion() != null, distance, average, count,
                branch.getLatitude() == null ? 0 : branch.getLatitude(), branch.getLongitude() == null ? 0 : branch.getLongitude());
    }

    static Review review(VisitRating rating) {
        List<String> tags = rating.getTags() == null || rating.getTags().isBlank() ? List.of() : List.of(rating.getTags().split(","));
        return new Review(rating.getStars(), tags, rating.getComment(), rating.getCreatedAt(), rating.getReply());
    }

    private static DirectoryEntry entry(Branch branch) {
        return new DirectoryEntry(branch.getId(), branch.getName(), branch.getLatitude(), branch.getLongitude(),
                branch.getAddress(), branch.isListed());
    }

    /** Distance on the earth's surface (haversine), in metres. */
    static double metres(double lat1, double lng1, Double lat2, Double lng2) {
        double r = 6_371_000;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

}
