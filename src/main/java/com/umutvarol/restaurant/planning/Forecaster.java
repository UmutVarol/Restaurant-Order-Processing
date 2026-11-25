package com.umutvarol.restaurant.planning;

import java.util.List;

/**
 * How many of a dish a day will likely sell. The same weekday of the last six weeks the restaurant was open counts
 * most (a Friday is like other Fridays), the recent weeks more than the older ones; the last two weeks against the
 * two before nudge it up or down, by half the change and at most 15 %. With fewer than two such weekdays it falls back
 * on the last days, and says the estimate is rough.
 */
final class Forecaster {

    enum Confidence {
        /** Four or more of the same weekday. */
        GOOD,
        /** Two or three of the same weekday, or only recent days. */
        LOW,
        /** No history at all. */
        NONE
    }

    /** @param low the quietest of the weekdays it is based on, {@code high} the busiest, both with the trend */
    record Estimate(int expected, int low, int high, Confidence confidence, int weeks) {
    }

    static final int WEEKS = 6;

    private Forecaster() {
    }

    /**
     * @param sameWeekday how many were made on the same weekday in past weeks the restaurant was open, most recent first
     * @param recentDays  how many were made on each recent open day, for when there are too few weekdays
     * @param trend       the factor from {@link #trend}
     */
    static Estimate estimate(List<Integer> sameWeekday, List<Integer> recentDays, double trend) {
        List<Integer> weeks = sameWeekday.subList(0, Math.min(WEEKS, sameWeekday.size()));
        if (weeks.size() >= 2) {
            double sum = 0;
            double weights = 0;
            for (int i = 0; i < weeks.size(); i++) {
                double weight = WEEKS - i;
                sum += weight * weeks.get(i);
                weights += weight;
            }
            int min = weeks.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = weeks.stream().mapToInt(Integer::intValue).max().orElse(0);
            return shape(sum / weights * trend, min * trend, max * trend, weeks.size() >= 4 ? Confidence.GOOD : Confidence.LOW, weeks.size());
        }
        if (!recentDays.isEmpty()) {
            double average = recentDays.stream().mapToInt(Integer::intValue).average().orElse(0);
            int min = recentDays.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = recentDays.stream().mapToInt(Integer::intValue).max().orElse(0);
            return shape(average * trend, min * trend, max * trend, Confidence.LOW, weeks.size());
        }
        return new Estimate(0, 0, 0, Confidence.NONE, 0);
    }

    /**
     * The last 14 open days against the 14 before, halved and kept within ±15 %: a busier fortnight lifts the
     * estimate a little, it never doubles it. 1 when either is empty.
     */
    static double trend(long lastFortnight, long fortnightBefore) {
        if (lastFortnight <= 0 || fortnightBefore <= 0) {
            return 1;
        }
        double ratio = (double) lastFortnight / fortnightBefore;
        return Math.clamp(1 + (ratio - 1) / 2, 0.85, 1.15);
    }

    private static Estimate shape(double expected, double low, double high, Confidence confidence, int weeks) {
        int value = (int) Math.round(expected);
        int from = Math.min(value, (int) Math.floor(low));
        int to = Math.max(value, (int) Math.ceil(high));
        return new Estimate(value, from, to, confidence, weeks);
    }
}
