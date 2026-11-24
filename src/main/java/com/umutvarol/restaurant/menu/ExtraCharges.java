package com.umutvarol.restaurant.menu;

import com.umutvarol.restaurant.common.error.BusinessRuleException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Since 30 January 2026 the Fiyat Etiketi Yönetmeliği forbids charging a service, cover (kuver) or table fee under
 * any such name: the menu price is the whole price. A dish or an option named like one of those fees is refused, so it
 * cannot be put on a bill by mistake.
 */
final class ExtraCharges {

    // matched after Turkish letters are folded (ü→u, ı→i, …), so "SERVİS ÜCRETİ" and "servis ucreti" both count
    private static final Pattern FEE = Pattern.compile(
            "\\b(servis (ucreti|bedeli)|hizmet (ucreti|bedeli)|masa (ucreti|bedeli)|kuver|kuvert|couvert"
                    + "|cover charge|service (charge|fee)|table (charge|fee))\\b");
    private static final Map<Character, Character> FOLD = Map.of(
            'ç', 'c', 'ğ', 'g', 'ı', 'i', 'ö', 'o', 'ş', 's', 'ü', 'u', 'â', 'a', 'î', 'i', 'û', 'u');

    private ExtraCharges() {
    }

    /** @throws BusinessRuleException {@code MENU_CHARGE_NOT_ALLOWED} when the name is one of the forbidden fees */
    static void refuse(@Nullable String name) {
        if (name != null && isFee(name)) {
            throw new BusinessRuleException("MENU_CHARGE_NOT_ALLOWED",
                    "A service, cover or table charge cannot be on the menu: the menu price is the whole price",
                    Map.of("name", name.strip()));
        }
    }

    static boolean isFee(String name) {
        StringBuilder folded = new StringBuilder();
        for (char c : name.toLowerCase(Locale.forLanguageTag("tr")).toCharArray()) {
            folded.append(FOLD.getOrDefault(c, c));
        }
        return FEE.matcher(folded.toString().replaceAll("\\s+", " ")).find();
    }
}
