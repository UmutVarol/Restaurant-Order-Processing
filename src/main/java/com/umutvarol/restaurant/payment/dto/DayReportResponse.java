package com.umutvarol.restaurant.payment.dto;

import com.umutvarol.restaurant.payment.DayClosing;
import com.umutvarol.restaurant.payment.DaySummary;
import java.math.BigDecimal;
import java.time.Instant;

/** The Z report: takings per method for a day and, once the day is closed, the cash count. */
public record DayReportResponse(DaySummary summary, Closing closing) {

    public record Closing(BigDecimal countedCash, BigDecimal variance, String note, Instant closedAt, String closedBy) {

        public static Closing from(DayClosing closing) {
            return new Closing(closing.getCountedCash(), closing.getVariance(), closing.getNote(),
                    closing.getClosedAt(), closing.getClosedBy());
        }
    }
}
