package com.umutvarol.restaurant.staff;

import com.umutvarol.restaurant.common.config.RestaurantProperties;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.security.AppUser;
import com.umutvarol.restaurant.security.AppUserRepository;
import com.umutvarol.restaurant.staff.dto.ShiftRequest;
import com.umutvarol.restaurant.staff.dto.ShiftResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The rota. Managers plan it; everyone can read it. Nobody is planned for two shifts at once. */
@Service
@Transactional
public class ShiftService {

    static final Duration LONGEST_SHIFT = Duration.ofHours(16);
    private static final Logger audit = LoggerFactory.getLogger("staff.audit");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final ShiftRepository shifts;
    private final AppUserRepository users;
    private final Clock clock;
    private final ZoneId zone;

    public ShiftService(ShiftRepository shifts, AppUserRepository users, Clock clock, RestaurantProperties restaurant) {
        this.shifts = shifts;
        this.users = users;
        this.clock = clock;
        this.zone = restaurant.timeZone();
    }

    /** The rota for the days [from, to], in the restaurant's time zone. */
    @Transactional(readOnly = true)
    public List<ShiftResponse> list(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(62).isBefore(to)) {
            throw new BusinessRuleException("PERIOD_INVALID", "Choose a period of up to two months");
        }
        return shifts.findByStartsAtBeforeAndEndsAtAfterOrderByStartsAtAsc(
                        to.plusDays(1).atStartOfDay(zone).toInstant(), from.atStartOfDay(zone).toInstant())
                .stream().map(ShiftService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    List<ShiftResponse> upcomingFor(Long userId, Instant from, Instant to) {
        return shifts.findByStartsAtBeforeAndEndsAtAfterOrderByStartsAtAsc(to, from).stream()
                .filter(s -> s.getUser().getId().equals(userId))
                .map(ShiftService::toResponse)
                .toList();
    }

    public ShiftResponse create(ShiftRequest request, String actor) {
        AppUser user = activeUser(request.userId());
        check(user, request.startsAt(), request.endsAt(), null);
        Shift shift = shifts.save(new Shift(user, request.startsAt(), request.endsAt(), text(request.note()), actor,
                clock.instant()));
        audit.info("shift.planned id={} user={} from={} to={} by={}", shift.getId(), user.getUsername(),
                request.startsAt(), request.endsAt(), actor);
        return toResponse(shift);
    }

    public ShiftResponse update(Long id, ShiftRequest request, String actor) {
        Shift shift = find(id);
        AppUser user = activeUser(request.userId());
        check(user, request.startsAt(), request.endsAt(), id);
        shift.reassign(user);
        shift.plan(request.startsAt(), request.endsAt(), text(request.note()));
        audit.info("shift.changed id={} user={} from={} to={} by={}", id, user.getUsername(), request.startsAt(),
                request.endsAt(), actor);
        return toResponse(shift);
    }

    public void delete(Long id, String actor) {
        Shift shift = find(id);
        shifts.delete(shift);
        audit.info("shift.removed id={} user={} by={}", id, shift.getUser().getUsername(), actor);
    }

    private void check(AppUser user, Instant start, Instant end, @Nullable Long self) {
        if (!end.isAfter(start) || Duration.between(start, end).compareTo(LONGEST_SHIFT) > 0) {
            throw new BusinessRuleException("SHIFT_INVALID", "A shift ends after it starts and lasts at most 16 hours");
        }
        shifts.findByUserIdAndStartsAtBeforeAndEndsAtAfter(user.getId(), end, start).stream()
                .filter(other -> !other.getId().equals(self))
                .findFirst()
                .ifPresent(other -> {
                    String from = TIME.format(other.getStartsAt().atZone(zone));
                    String to = TIME.format(other.getEndsAt().atZone(zone));
                    throw new InvalidStateException("SHIFT_OVERLAP",
                            "%s already works %s–%s".formatted(user.getDisplayName(), from, to),
                            Map.of("name", user.getDisplayName(), "from", from, "to", to));
                });
    }

    private AppUser activeUser(Long id) {
        return users.findById(id).filter(AppUser::isEnabled).orElseThrow(() -> new ResourceNotFoundException("staff", id));
    }

    private Shift find(Long id) {
        return shifts.findById(id).orElseThrow(() -> new ResourceNotFoundException("shift", id));
    }

    private static @Nullable String text(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static ShiftResponse toResponse(Shift s) {
        AppUser u = s.getUser();
        return new ShiftResponse(s.getId(), u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(), s.getStartsAt(),
                s.getEndsAt(), s.getNote());
    }
}
