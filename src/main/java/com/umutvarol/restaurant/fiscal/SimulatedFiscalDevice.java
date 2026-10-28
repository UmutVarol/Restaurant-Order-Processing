package com.umutvarol.restaurant.fiscal;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Demo and tests: issues numbered documents without a device or an integrator. Nothing is declared to anyone, and
 * the screens say so.
 */
class SimulatedFiscalDevice implements FiscalDevice {

    private final FiscalKind kind;
    private final Clock clock;
    private final Map<String, Issued> issued = new ConcurrentHashMap<>();
    private final Map<LocalDate, BigDecimal> totals = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    SimulatedFiscalDevice(FiscalKind kind, Clock clock) {
        this.kind = kind;
        this.clock = clock;
    }

    @Override
    public String provider() {
        return "SIMULATED";
    }

    @Override
    public Issued issue(Request request) {
        return issued.computeIfAbsent(request.key(), key -> {
            long number = sequence.incrementAndGet();
            totals.merge(LocalDate.ofInstant(request.at(), zone()), request.total(), BigDecimal::add);
            return kind == FiscalKind.RECEIPT
                    ? new Issued("SIM-OKC-" + number, "F%06d".formatted(number), null)
                    : new Issued("SIM-EARSIV-" + number, "SIM%d%09d".formatted(LocalDate.now(clock).getYear(), number), null);
        });
    }

    @Override
    public Optional<BigDecimal> dailyTotal(LocalDate day) {
        return kind == FiscalKind.RECEIPT ? Optional.of(totals.getOrDefault(day, BigDecimal.ZERO)) : Optional.empty();
    }

    private ZoneId zone() {
        return clock.getZone();
    }
}
