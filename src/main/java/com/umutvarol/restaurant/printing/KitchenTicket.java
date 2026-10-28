package com.umutvarol.restaurant.printing;

import com.umutvarol.restaurant.order.Order;
import com.umutvarol.restaurant.order.OrderLine;
import com.umutvarol.restaurant.order.OrderLineOption;
import com.umutvarol.restaurant.order.OrderType;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What a kitchen or bar ticket says, laid out once and rendered twice: as ESC/POS bytes for the printer and as
 * plain text for the preview in Settings. Big type for what the cook acts on (where it goes, how many of what),
 * small type for the rest.
 */
final class KitchenTicket {

    enum Style { TITLE, HEADLINE, ITEM, TEXT, DETAIL, RULE }

    record Row(Style style, String text) {
    }

    private static final Map<String, Map<String, String>> WORDS = Map.of(
            "tr", Map.of("TABLE", "MASA", "TAKEAWAY", "PAKET", "DELIVERY", "KURYE", "REPRINT", "TEKRAR BASKI",
                    "NOTE", "NOT", "TEST", "TEST SAYFASI", "TEST_OK", "Yazici hazir. Turkce: ÇĞİÖŞÜ çğıöşü",
                    "CATEGORIES", "Kategoriler"),
            "en", Map.of("TABLE", "TABLE", "TAKEAWAY", "TAKEAWAY", "DELIVERY", "DELIVERY", "REPRINT", "REPRINT",
                    "NOTE", "NOTE", "TEST", "TEST PAGE", "TEST_OK", "The printer is ready. Turkish: ÇĞİÖŞÜ çğıöşü",
                    "CATEGORIES", "Categories"));
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final List<Row> rows = new ArrayList<>();

    private KitchenTicket() {
    }

    /** The lines of {@code order} this printer makes; voided units are left out. */
    static KitchenTicket forOrder(Printer printer, Order order, List<OrderLine> lines, boolean reprint, String language,
                                  ZoneId zone) {
        Map<String, String> w = words(language);
        KitchenTicket ticket = new KitchenTicket();
        ticket.add(Style.TITLE, printer.getName().toUpperCase());
        if (reprint) {
            ticket.add(Style.TITLE, "*** " + w.get("REPRINT") + " ***");
        }
        String where = order.getType() == OrderType.DINE_IN && order.getTable() != null
                ? w.get("TABLE") + " " + order.getTable().getCode()
                : w.get(order.getType().name()) + " #" + order.getId();
        ticket.add(Style.HEADLINE, where);
        ticket.add(Style.TEXT, "#%d  %s  %s  %s".formatted(order.getId(), TIME.format(order.getCreatedAt().atZone(zone)),
                order.getCreatedBy() == null ? "" : order.getCreatedBy(), order.getCustomerName()).strip());
        ticket.add(Style.RULE, "");
        for (OrderLine line : lines) {
            if (line.activeQuantity() <= 0) {
                continue;
            }
            ticket.add(Style.ITEM, line.activeQuantity() + " x " + line.getItemName());
            if (!line.getOptions().isEmpty()) {
                ticket.add(Style.DETAIL, "+ " + line.getOptions().stream().map(OrderLineOption::getChoiceName)
                        .collect(Collectors.joining(", ")));
            }
            if (line.getNote() != null) {
                ticket.add(Style.DETAIL, "! " + line.getNote());
            }
        }
        if (order.getNote() != null) {
            ticket.add(Style.RULE, "");
            ticket.add(Style.TEXT, w.get("NOTE") + ": " + order.getNote());
        }
        return ticket;
    }

    static KitchenTicket test(Printer printer, String language, Instant at, ZoneId zone) {
        Map<String, String> w = words(language);
        KitchenTicket ticket = new KitchenTicket();
        ticket.add(Style.TITLE, printer.getName().toUpperCase());
        ticket.add(Style.HEADLINE, w.get("TEST"));
        ticket.add(Style.TEXT, TIME.format(at.atZone(zone)) + "  " + printer.getHost() + ":" + printer.getPort());
        ticket.add(Style.RULE, "");
        ticket.add(Style.TEXT, w.get("TEST_OK"));
        ticket.add(Style.TEXT, w.get("CATEGORIES") + ": " + printer.categories().stream().map(Enum::name)
                .collect(Collectors.joining(", ")));
        return ticket;
    }

    byte[] escPos(int columns) {
        EscPos out = new EscPos();
        for (Row row : rows) {
            switch (row.style()) {
                case TITLE -> out.align('C').bold(true).line(row.text()).bold(false).align('L');
                case HEADLINE -> wrap(row.text(), columns / 2).forEach(l -> out.align('C').big(true).line(l).big(false).align('L'));
                case ITEM -> wrap(row.text(), columns / 2).forEach(l -> out.big(true).line(l).big(false));
                case TEXT -> wrap(row.text(), columns).forEach(out::line);
                case DETAIL -> wrap(row.text(), columns - 3).forEach(l -> out.line("   " + l));
                case RULE -> out.line("-".repeat(columns));
            }
        }
        return out.feed(4).cut();
    }

    /** The same ticket as text, for the preview; big rows are marked rather than spaced out. */
    String text(int columns) {
        StringBuilder out = new StringBuilder();
        for (Row row : rows) {
            switch (row.style()) {
                case TITLE -> out.append(center(row.text(), columns)).append('\n');
                case HEADLINE -> wrap(row.text(), columns / 2).forEach(l -> out.append(center(l.toUpperCase(), columns)).append('\n'));
                case ITEM -> wrap(row.text(), columns / 2).forEach(l -> out.append(l.toUpperCase()).append('\n'));
                case TEXT -> wrap(row.text(), columns).forEach(l -> out.append(l).append('\n'));
                case DETAIL -> wrap(row.text(), columns - 3).forEach(l -> out.append("   ").append(l).append('\n'));
                case RULE -> out.append("-".repeat(columns)).append('\n');
            }
        }
        return out.toString();
    }

    List<Row> rows() {
        return List.copyOf(rows);
    }

    private void add(Style style, String text) {
        rows.add(new Row(style, text.replaceAll("\\p{Cntrl}", " ")));
    }

    private static Map<String, String> words(String language) {
        return WORDS.getOrDefault(language, WORDS.get("tr"));
    }

    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" +")) {
            while (word.length() > width) {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (current.length() + word.length() + (current.isEmpty() ? 0 : 1) > width) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(word);
        }
        if (!current.isEmpty() || lines.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static String center(String text, int width) {
        int pad = Math.max(0, (width - text.length()) / 2);
        return " ".repeat(pad) + text;
    }
}
