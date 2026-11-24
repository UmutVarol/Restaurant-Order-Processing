package com.umutvarol.restaurant.compliance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "e-Adisyon", description = "The electronic adisyon of every table, for a business GİB notified (VUK 509).")
@RestController
class AdisyonController {

    private final AdisyonService adisyons;
    private final Clock clock;

    AdisyonController(AdisyonService adisyons, Clock clock) {
        this.adisyons = adisyons;
        this.clock = clock;
    }

    @Operation(summary = "A day's e-Adisyons", description = "Whether the business keeps them, the day's adisyons and any "
            + "older one whose latest version the integrator has not accepted yet.")
    @GetMapping("/api/v1/adisyon")
    AdisyonService.View day(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate date) {
        return adisyons.day(date == null ? LocalDate.now(clock) : date);
    }

    @Operation(summary = "Send an e-Adisyon again now")
    @PostMapping("/api/v1/adisyon/{id}/retry")
    AdisyonService.Entry retry(@PathVariable Long id) {
        return adisyons.retry(id);
    }
}
