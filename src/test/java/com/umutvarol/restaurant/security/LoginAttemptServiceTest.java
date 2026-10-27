package com.umutvarol.restaurant.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class LoginAttemptServiceTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T12:00:00Z"));
    private final LoginAttemptService attempts = new LoginAttemptService(new AbuseProtectionProperties(
            new AbuseProtectionProperties.Login(3, Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(2)),
            new AbuseProtectionProperties.RateLimit(true, 600, 60),
            DataSize.ofKilobytes(64)), clock);

    @Test
    void locksTheAccountAfterTooManyFailuresInTheWindow() {
        assertThat(attempts.failed("waiter")).isEmpty();
        assertThat(attempts.failed("waiter")).isEmpty();
        assertThat(attempts.failed("WAITER ")).contains(Duration.ofSeconds(30));

        assertThat(attempts.lockedFor("Waiter")).contains(Duration.ofSeconds(30));
        clock.advance(Duration.ofSeconds(31));
        assertThat(attempts.lockedFor("waiter")).isEmpty();
    }

    @Test
    void eachFurtherLockoutDoublesUpToTheMaximum() {
        assertThat(lockOut()).isEqualTo(Duration.ofSeconds(30));
        assertThat(lockOut()).isEqualTo(Duration.ofSeconds(60));
        assertThat(lockOut()).isEqualTo(Duration.ofSeconds(120));
        assertThat(lockOut()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void failuresOutsideTheObservationWindowAreForgotten() {
        attempts.failed("chef");
        attempts.failed("chef");
        clock.advance(Duration.ofMinutes(16));
        assertThat(attempts.failed("chef")).isEmpty();
        assertThat(attempts.lockedFor("chef")).isEmpty();
    }

    @Test
    void aSuccessfulSignInClearsTheHistory() {
        attempts.failed("admin");
        attempts.failed("admin");
        attempts.succeeded("admin");
        assertThat(attempts.failed("admin")).isEmpty();
    }

    @Test
    void unknownUsernamesAreTrackedLikeRealOnes() {
        for (int i = 0; i < 3; i++) {
            attempts.failed("no-such-user");
        }
        assertThat(attempts.lockedFor("no-such-user")).isPresent();
    }

    private Duration lockOut() {
        Duration lockout = null;
        for (int i = 0; i < 3; i++) {
            lockout = attempts.failed("cashier").orElse(null);
        }
        clock.advance(lockout);
        return lockout;
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
