package com.umutvarol.restaurant.printing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The few ESC/POS commands a kitchen ticket needs, written by hand so there is no driver to install. Text goes out
 * in code page 857 (Turkish), selected with {@code ESC t 13}, which Epson-compatible printers understand.
 */
final class EscPos {

    private static final byte ESC = 0x1b;
    private static final byte GS = 0x1d;
    private static final Map<Character, Integer> CP857 = Map.ofEntries(
            Map.entry('Ç', 0x80), Map.entry('ü', 0x81), Map.entry('é', 0x82), Map.entry('â', 0x83),
            Map.entry('ä', 0x84), Map.entry('à', 0x85), Map.entry('ç', 0x87), Map.entry('ê', 0x88),
            Map.entry('ë', 0x89), Map.entry('è', 0x8a), Map.entry('ï', 0x8b), Map.entry('î', 0x8c),
            Map.entry('ı', 0x8d), Map.entry('Ä', 0x8e), Map.entry('É', 0x90), Map.entry('ô', 0x93),
            Map.entry('ö', 0x94), Map.entry('ò', 0x95), Map.entry('û', 0x96), Map.entry('ù', 0x97),
            Map.entry('İ', 0x98), Map.entry('Ö', 0x99), Map.entry('Ü', 0x9a), Map.entry('Ş', 0x9e),
            Map.entry('ş', 0x9f), Map.entry('á', 0xa0), Map.entry('í', 0xa1), Map.entry('ó', 0xa2),
            Map.entry('ú', 0xa3), Map.entry('ñ', 0xa4), Map.entry('Ñ', 0xa5), Map.entry('Ğ', 0xa6),
            Map.entry('ğ', 0xa7), Map.entry('Â', 0xb6), Map.entry('Î', 0xd7), Map.entry('Û', 0xea));

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    EscPos() {
        raw(ESC, '@');          // reset
        raw(ESC, 't', 13);      // code page 857
    }

    EscPos align(char how) {
        return raw(ESC, 'a', how == 'C' ? 1 : how == 'R' ? 2 : 0);
    }

    EscPos bold(boolean on) {
        return raw(ESC, 'E', on ? 1 : 0);
    }

    /** Double width and height, for what the cook must see from a step away. */
    EscPos big(boolean on) {
        return raw(GS, '!', on ? 0x11 : 0);
    }

    EscPos line(String text) {
        text(text);
        out.write('\n');
        return this;
    }

    EscPos feed(int lines) {
        return raw(ESC, 'd', lines);
    }

    /** Feeds past the cutter and cuts, leaving a small tab (partial cut). */
    byte[] cut() {
        raw(GS, 'V', 66, 3);
        return out.toByteArray();
    }

    private void text(String text) {
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c < 0x7f) {
                out.write(c);
            } else if (c == '₺') {
                out.writeBytes("TL".getBytes(StandardCharsets.US_ASCII));
            } else if (c == '×') {
                out.write('x');
            } else if (c == '·' || c == '–' || c == '—') {
                out.write('-');
            } else if (CP857.containsKey(c)) {
                out.write(CP857.get(c));
            } else if (!Character.isSurrogate(c) && !Character.isISOControl(c) && Character.getType(c) != Character.OTHER_SYMBOL) {
                out.write('?');
            }
            // emoji and other symbols are dropped: a ticket printer cannot draw them
        }
    }

    private EscPos raw(int... bytes) {
        for (int b : bytes) {
            out.write(b);
        }
        return this;
    }
}
