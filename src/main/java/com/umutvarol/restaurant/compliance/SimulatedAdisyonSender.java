package com.umutvarol.restaurant.compliance;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Accepts every version and numbers it like an integrator would: nothing reaches GİB. */
class SimulatedAdisyonSender implements AdisyonSender {

    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("yyyy", Locale.ROOT);

    private final Clock clock;

    SimulatedAdisyonSender(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String provider() {
        return "SIMULATED";
    }

    @Override
    public Accepted send(Request request) {
        String year = YEAR.format(clock.instant().atZone(ZoneId.of("Europe/Istanbul")));
        return new Accepted("EAD" + year + "%09d".formatted(request.billId()));
    }
}
