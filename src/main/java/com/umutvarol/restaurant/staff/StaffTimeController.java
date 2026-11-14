package com.umutvarol.restaurant.staff;

import com.umutvarol.restaurant.staff.dto.ClockStatus;
import com.umutvarol.restaurant.staff.dto.CorrectionRequest;
import com.umutvarol.restaurant.staff.dto.HoursRow;
import com.umutvarol.restaurant.staff.dto.ShiftRequest;
import com.umutvarol.restaurant.staff.dto.ShiftResponse;
import com.umutvarol.restaurant.staff.dto.TimeEntryResponse;
import com.umutvarol.restaurant.staff.dto.TipPoolRequest;
import com.umutvarol.restaurant.staff.dto.TipPoolResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Staff time", description = "The time clock, the rota and tip pooling")
@RestController
class StaffTimeController {

    private final TimeClockService clock;
    private final ShiftService shifts;
    private final TipPoolService tips;

    StaffTimeController(TimeClockService clock, ShiftService shifts, TipPoolService tips) {
        this.clock = clock;
        this.shifts = shifts;
        this.tips = tips;
    }

    @Operation(summary = "My time clock", description = "Clocked in or not, this week's hours, upcoming shifts, my tips.")
    @GetMapping("/api/v1/time-clock/me")
    ClockStatus me(Authentication authentication) {
        return clock.status(authentication.getName());
    }

    @Operation(summary = "Clock in")
    @PostMapping("/api/v1/time-clock/in")
    ClockStatus clockIn(Authentication authentication) {
        return clock.clockIn(authentication.getName());
    }

    @Operation(summary = "Clock out")
    @PostMapping("/api/v1/time-clock/out")
    ClockStatus clockOut(Authentication authentication) {
        return clock.clockOut(authentication.getName());
    }

    @Operation(summary = "Time entries", description = "Everyone's, for the days given (managers).")
    @GetMapping("/api/v1/time-clock/entries")
    List<TimeEntryResponse> entries(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return clock.entries(from, to);
    }

    @Operation(summary = "Correct a time entry", description = "With a reason, which stays on the entry (managers).")
    @PutMapping("/api/v1/time-clock/entries/{id}")
    TimeEntryResponse correct(@PathVariable Long id, @Valid @RequestBody CorrectionRequest request,
                              Authentication authentication) {
        return clock.correct(id, request, authentication.getName());
    }

    @Operation(summary = "Clock someone out", description = "For someone who left without clocking out (managers).")
    @PostMapping("/api/v1/time-clock/users/{userId}/out")
    TimeEntryResponse clockOutFor(@PathVariable Long userId, Authentication authentication) {
        return clock.clockOutFor(userId, authentication.getName());
    }

    @Operation(summary = "Hours worked", description = "Per person, for payroll (managers).")
    @GetMapping("/api/v1/time-clock/report")
    List<HoursRow> report(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return clock.report(from, to);
    }

    @Operation(summary = "The rota")
    @GetMapping("/api/v1/shifts")
    List<ShiftResponse> shifts(@RequestParam LocalDate from, @RequestParam LocalDate to) {
        return shifts.list(from, to);
    }

    @Operation(summary = "Plan a shift", description = "Managers.")
    @PostMapping("/api/v1/shifts")
    @ResponseStatus(HttpStatus.CREATED)
    ShiftResponse plan(@Valid @RequestBody ShiftRequest request, Authentication authentication) {
        return shifts.create(request, authentication.getName());
    }

    @Operation(summary = "Change a shift", description = "Managers.")
    @PutMapping("/api/v1/shifts/{id}")
    ShiftResponse change(@PathVariable Long id, @Valid @RequestBody ShiftRequest request, Authentication authentication) {
        return shifts.update(id, request, authentication.getName());
    }

    @Operation(summary = "Remove a shift", description = "Managers.")
    @DeleteMapping("/api/v1/shifts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@PathVariable Long id, Authentication authentication) {
        shifts.delete(id, authentication.getName());
    }

    @Operation(summary = "A day's tips", description = "A preview of the shares until the day is closed (managers).")
    @GetMapping("/api/v1/tips/pool")
    TipPoolResponse pool(@RequestParam LocalDate date) {
        return tips.day(date);
    }

    @Operation(summary = "Share out a day's tips", description = "Fixes the shares, for payroll (managers).")
    @PostMapping("/api/v1/tips/pool")
    TipPoolResponse close(@Valid @RequestBody TipPoolRequest request, Authentication authentication) {
        return tips.close(request.date(), authentication.getName());
    }
}
