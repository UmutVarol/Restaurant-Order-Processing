package com.umutvarol.restaurant.compliance;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Price list", description = "The price list at the door and on the tables, and its export for the Ministry of Trade.")
@RestController
class PriceListController {

    private final PriceListService prices;

    PriceListController(PriceListService prices) {
        this.prices = prices;
    }

    @Operation(summary = "A branch's price list", description = "Every dish with its price in lira, VAT included, and "
            + "when the prices last changed. Without a branch, the branch the user works in (or the first open one).")
    @GetMapping("/api/v1/price-list")
    PriceListService.PriceList priceList(@RequestParam(required = false) @Nullable Long branchId) {
        return prices.of(branchId);
    }

    @Operation(summary = "The price list as a file", description = "format=csv (Turkish spreadsheet convention) or json "
            + "(schema " + PriceListExport.SCHEMA + "), for the Ministry of Trade's electronic price list.")
    @GetMapping("/api/v1/price-list/export")
    ResponseEntity<?> export(@RequestParam(required = false) @Nullable Long branchId,
                             @RequestParam(defaultValue = "csv") String format) {
        PriceListService.PriceList list = prices.of(branchId);
        String name = "fiyat-listesi-" + list.branchCode();
        if ("json".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(name + ".json"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(PriceListExport.json(list));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(name + ".csv"))
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(PriceListExport.csv(list));
    }

    private static String attachment(String filename) {
        return ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString();
    }
}
