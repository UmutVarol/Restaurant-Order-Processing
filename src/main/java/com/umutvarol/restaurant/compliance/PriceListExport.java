package com.umutvarol.restaurant.compliance;

import com.umutvarol.restaurant.menu.MenuCategory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The price list in machine-readable form, for the Ministry of Trade's electronic price list and for whoever keeps
 * the restaurant's records. The Ministry has not published a submission format yet: these files carry everything a
 * list must show (each dish, its price in lira VAT included, its VAT rate and when its price last changed) so they can
 * be sent, or converted, once it does.
 */
final class PriceListExport {

    static final String SCHEMA = "sofra.price-list/1";

    private static final Map<MenuCategory, String> CATEGORY = Map.of(
            MenuCategory.STARTER, "Başlangıç", MenuCategory.MAIN, "Ana yemek",
            MenuCategory.DESSERT, "Tatlı", MenuCategory.DRINK, "İçecek");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    /** What the JSON file holds. Prices are numbers in lira with VAT included. */
    record Document(String schema, String currency, boolean vatIncluded, String business, String branch,
                    String branchCode, @Nullable String address, @Nullable Instant lastChangedAt, Instant generatedAt,
                    List<Line> items) {
    }

    record Line(String category, String name, BigDecimal price, BigDecimal vatRate, boolean onSale,
                @Nullable Instant priceChangedAt) {
    }

    private PriceListExport() {
    }

    static Document json(PriceListService.PriceList list) {
        return new Document(SCHEMA, "TRY", true, list.business(), list.branch(), list.branchCode(), list.address(),
                list.lastChangedAt(), list.generatedAt(),
                list.items().stream().map(item -> new Line(item.category().name(), item.name(), item.price(),
                        item.vatRate(), item.available(), item.priceChangedAt())).toList());
    }

    /**
     * A spreadsheet in the Turkish convention: semicolons between columns and a decimal comma, with a byte-order mark
     * so Excel reads the Turkish letters.
     */
    static String csv(PriceListService.PriceList list) {
        ZoneId zone = ZoneId.of(list.timeZone());
        StringBuilder out = new StringBuilder("﻿");
        out.append("Şube;Kategori;Ürün;Fiyat (TL, KDV dahil);KDV oranı (%);Satışta;Son fiyat değişikliği\r\n");
        for (PriceListService.Item item : list.items()) {
            out.append(cell(list.branch())).append(';')
                    .append(cell(CATEGORY.get(item.category()))).append(';')
                    .append(cell(item.name())).append(';')
                    .append(decimal(item.price())).append(';')
                    .append(decimal(item.vatRate().stripTrailingZeros())).append(';')
                    .append(item.available() ? "Evet" : "Hayır").append(';')
                    .append(item.priceChangedAt() == null ? "" : DAY.format(item.priceChangedAt().atZone(zone)))
                    .append("\r\n");
        }
        return out.toString();
    }

    private static String decimal(BigDecimal value) {
        return value.toPlainString().replace('.', ',');
    }

    /** Quoted when it holds a separator, a quote or a line break; a leading formula character is defused. */
    private static String cell(String value) {
        String safe = !value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        if (safe.contains(";") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }
}
