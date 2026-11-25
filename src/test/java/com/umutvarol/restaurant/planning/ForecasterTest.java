package com.umutvarol.restaurant.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ForecasterTest {

    @Test
    void recentWeeksCountMoreThanOlderOnes() {
        // weights 6,5,4,3: (6×20 + 5×10 + 4×10 + 3×10) / 18 = 13.3
        Forecaster.Estimate estimate = Forecaster.estimate(List.of(20, 10, 10, 10), List.of(), 1);
        assertThat(estimate.expected()).isEqualTo(13);
        assertThat(estimate.low()).isEqualTo(10);
        assertThat(estimate.high()).isEqualTo(20);
        assertThat(estimate.confidence()).isEqualTo(Forecaster.Confidence.GOOD);
        assertThat(estimate.weeks()).isEqualTo(4);
    }

    @Test
    void twoOrThreeWeeksAreARoughEstimateAndOneFallsBackOnRecentDays() {
        assertThat(Forecaster.estimate(List.of(8, 6), List.of(), 1).confidence()).isEqualTo(Forecaster.Confidence.LOW);
        Forecaster.Estimate fallback = Forecaster.estimate(List.of(9), List.of(4, 6, 5), 1);
        assertThat(fallback.expected()).isEqualTo(5);
        assertThat(fallback.confidence()).isEqualTo(Forecaster.Confidence.LOW);
        assertThat(Forecaster.estimate(List.of(), List.of(), 1).confidence()).isEqualTo(Forecaster.Confidence.NONE);
    }

    @Test
    void onlyTheLastSixWeeksCount() {
        assertThat(Forecaster.estimate(List.of(10, 10, 10, 10, 10, 10, 500), List.of(), 1).expected()).isEqualTo(10);
    }

    @Test
    void theTrendNudgesByHalfAndAtMostFifteenPercent() {
        assertThat(Forecaster.trend(120, 100)).isEqualTo(1.10);
        assertThat(Forecaster.trend(300, 100)).isEqualTo(1.15);
        assertThat(Forecaster.trend(10, 100)).isEqualTo(0.85);
        assertThat(Forecaster.trend(0, 100)).isEqualTo(1);
        assertThat(Forecaster.estimate(List.of(10, 10), List.of(), 1.1).expected()).isEqualTo(11);
    }
}
