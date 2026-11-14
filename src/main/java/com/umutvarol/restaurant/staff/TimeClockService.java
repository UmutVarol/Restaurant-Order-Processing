package com.umutvarol.restaurant.staff;

import com.umutvarol.restaurant.common.config.RestaurantProperties;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.common.error.InvalidStateException;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import com.umutvarol.restaurant.security.AppUser;
import com.umutvarol.restaurant.security.AppUserRepository;
import com.umutvarol.restaurant.staff.dto.ClockStatus;
import com.umutvarol.restaurant.staff.dto.CorrectionRequest;
import com.umutvarol.restaurant.staff.dto.HoursRow;
import com.umutvarol.restaurant.staff.dto.ShiftResponse;
import com.umutvarol.restaurant.staff.dto.TimeEntryResponse;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The time clock. Everyone clocks themselves in and out (on a shared till, the person signed in with their PIN);
 * nobody can be clocked in twice (a unique column guarantees it even for two taps at once). Managers see every
 * entry, clock out someone who forgot, and correct times, always with a reason that stays on the entry.
 */
@Service
@Transactional
public class TimeClockService {

    /** Longer than any real shift: an entry open this long was almost certainly not clocked out. */
    static final Duration FORGOTTEN = Duration.ofHours(14);
    /** Corrections may not invent a shift longer than this. */
    static final Duration LONGEST_ENTRY = Duration.ofHours(24);

    private static final Logger audit = LoggerFactory.getLogger("staff.audit");

    private final TimeEntryRepository entries;
    private final AppUserRepository users;
    private final ShiftService shifts;
    private final TipPoolService tips;
    private final Clock clock;
    private final ZoneId zone;

    public TimeClockService(TimeEntryRepository entries, AppUserRepository users, ShiftService shifts,
                            TipPoolService tips, Clock clock, RestaurantProperties restaurant) {
        this.entries = entries;
        this.users = users;
        this.shifts = shifts;
        this.tips = tips;
        this.clock = clock;
        this.zone = restaurant.timeZone();
    }

    public ClockStatus clockIn(String username) {
        AppUser user = user(username);
        if (entries.findByOpenUserId(user.getId()).isPresent()) {
            throw alreadyIn();
        }
        try {
            entries.saveAndFlush(new TimeEntry(user, clock.instant(), username));
        } catch (DataIntegrityViolationException raced) {
            throw alreadyIn();
        }
        audit.info("clock.in user={}", username);
        return status(username);
    }

    public ClockStatus clockOut(String username) {
        AppUser user = user(username);
        TimeEntry open = entries.findByOpenUserId(user.getId())
                .orElseThrow(() -> new InvalidStateException("NOT_CLOCKED_IN", "You are not clocked in", Map.of()));
        open.clockOut(clock.instant(), username);
        audit.info("clock.out user={}", username);
        return status(username);
    }

    /** A manager clocks out someone who left without doing it. */
    public TimeEntryResponse clockOutFor(Long userId, String actor) {
        TimeEntry open = entries.findByOpenUserId(userId)
                .orElseThrow(() -> new InvalidStateException("NOT_CLOCKED_IN", "Not clocked in", Map.of()));
        open.clockOut(clock.instant(), actor);
        audit.info("clock.out user={} by={}", open.getUser().getUsername(), actor);
        return toResponse(open, clock.instant());
    }

    public TimeEntryResponse correct(Long id, CorrectionRequest request, String actor) {
        TimeEntry entry = entries.findById(id).orElseThrow(() -> new ResourceNotFoundException("time_entry", id));
        Instant now = clock.instant();
        Instant in = request.clockedInAt();
        Instant out = request.clockedOutAt();
        if (in.isAfter(now) || (out != null && out.isAfter(now.plusSeconds(60)))) {
            throw new BusinessRuleException("TIME_IN_FUTURE", "Times cannot be in the future");
        }
        if (out != null && (!out.isAfter(in) || Duration.between(in, out).compareTo(LONGEST_ENTRY) > 0)) {
            throw new BusinessRuleException("TIME_ENTRY_INVALID", "The end must be after the start and within a day");
        }
        if (out == null && !entry.isOpen()) {
            // reopening would collide with an entry opened since
            entries.findByOpenUserId(entry.getUser().getId()).ifPresent(other -> {
                throw alreadyIn();
            });
        }
        entry.correct(in, out, request.reason().strip(), actor);
        audit.info("clock.corrected id={} user={} reason=\"{}\" by={}", id, entry.getUser().getUsername(),
                request.reason().strip().replaceAll("[\\r\\n]", " "), actor);
        return toResponse(entry, now);
    }

    @Transactional(readOnly = true)
    public ClockStatus status(String username) {
        AppUser user = user(username);
        Instant now = clock.instant();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant dayStart = today.atStartOfDay(zone).toInstant();
        Instant weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant();
        List<TimeEntry> week = entries.overlapping(weekStart, now.plusSeconds(1)).stream()
                .filter(e -> e.getUser().getId().equals(user.getId()))
                .toList();
        long todayMinutes = week.stream().mapToLong(e -> e.within(dayStart, now, now).toMinutes()).sum();
        long weekMinutes = week.stream().mapToLong(e -> e.within(weekStart, now, now).toMinutes()).sum();
        TimeEntry open = week.stream().filter(TimeEntry::isOpen).findFirst().orElse(null);
        List<ShiftResponse> upcoming = shifts.upcomingFor(user.getId(), now, now.plus(Duration.ofDays(7)));
        List<ClockStatus.MyTip> myTips = tips.sharesOf(user.getId(), today.minusDays(13), today);
        return new ClockStatus(open != null, open == null ? null : open.getClockedInAt(), todayMinutes, weekMinutes,
                week.stream().sorted(Comparator.comparing(TimeEntry::getClockedInAt).reversed())
                        .map(e -> toResponse(e, now)).toList(),
                upcoming, myTips);
    }

    /** Entries in the period, newest first, plus everyone clocked in right now. */
    @Transactional(readOnly = true)
    public List<TimeEntryResponse> entries(LocalDate from, LocalDate to) {
        Instant now = clock.instant();
        return entries.overlapping(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
                .stream()
                .sorted(Comparator.comparing(TimeEntry::getClockedInAt).reversed())
                .map(e -> toResponse(e, now))
                .toList();
    }

    /** Hours per person in the period, the part of each entry inside it, for payroll. */
    @Transactional(readOnly = true)
    public List<HoursRow> report(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || from.plusDays(62).isBefore(to)) {
            throw new BusinessRuleException("PERIOD_INVALID", "Choose a period of up to two months");
        }
        Instant start = from.atStartOfDay(zone).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(zone).toInstant();
        Instant now = clock.instant();
        Map<Long, long[]> byUser = new LinkedHashMap<>();
        Map<Long, AppUser> people = new LinkedHashMap<>();
        Map<Long, Boolean> open = new LinkedHashMap<>();
        for (TimeEntry e : entries.overlapping(start, end)) {
            Long id = e.getUser().getId();
            people.putIfAbsent(id, e.getUser());
            long[] totals = byUser.computeIfAbsent(id, k -> new long[2]);
            totals[0] += e.within(start, end, now).toMinutes();
            totals[1]++;
            open.merge(id, e.isOpen(), Boolean::logicalOr);
        }
        return people.values().stream()
                .map(u -> new HoursRow(u.getId(), u.getUsername(), u.getDisplayName(), u.getRole(),
                        byUser.get(u.getId())[0], (int) byUser.get(u.getId())[1], open.get(u.getId())))
                .sorted(Comparator.comparing(HoursRow::displayName))
                .toList();
    }

    private AppUser user(String username) {
        return users.findByUsernameIgnoreCase(username).orElseThrow(() -> new ResourceNotFoundException("user", username));
    }

    private static InvalidStateException alreadyIn() {
        return new InvalidStateException("ALREADY_CLOCKED_IN", "Already clocked in", Map.of());
    }

    static TimeEntryResponse toResponse(TimeEntry e, Instant now) {
        Instant end = e.getClockedOutAt() != null ? e.getClockedOutAt() : now;
        long minutes = Math.max(0, Duration.between(e.getClockedInAt(), end).toMinutes());
        boolean forgotten = e.isOpen() && Duration.between(e.getClockedInAt(), now).compareTo(FORGOTTEN) > 0;
        AppUser u = e.getUser();
        return new TimeEntryResponse(e.getId(), u.getId(), u.getUsername(), u.getDisplayName(), e.getClockedInAt(),
                e.getClockedOutAt(), minutes, forgotten, e.getClockedInBy(), e.getClockedOutBy(), e.getCorrectedBy(),
                e.getCorrectionReason());
    }
}
