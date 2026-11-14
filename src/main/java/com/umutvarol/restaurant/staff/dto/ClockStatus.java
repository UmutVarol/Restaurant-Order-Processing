package com.umutvarol.restaurant.staff.dto;

import java.time.Instant;
import java.util.List;

/** What the time clock shows the person using it. */
public record ClockStatus(boolean clockedIn, Instant since, long todayMinutes, long weekMinutes,
                          List<TimeEntryResponse> week, List<ShiftResponse> upcoming, List<MyTip> tips) {

    public record MyTip(java.time.LocalDate date, java.math.BigDecimal amount, int minutes) {
    }
}
