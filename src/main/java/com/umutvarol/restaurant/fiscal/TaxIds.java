package com.umutvarol.restaurant.fiscal;

import com.umutvarol.restaurant.common.error.BusinessRuleException;

/**
 * Turkish tax numbers: a company's VKN (10 digits) or a person's TCKN (11 digits), each with its check digits, so a
 * typo is caught at the till rather than by GİB.
 */
final class TaxIds {

    private TaxIds() {
    }

    /** @throws BusinessRuleException {@code TAX_ID_INVALID} for anything but a valid VKN or TCKN */
    static String requireValid(String value) {
        String digits = value == null ? "" : value.strip();
        if (!(digits.length() == 10 ? isVkn(digits) : digits.length() == 11 && isTckn(digits))) {
            throw new BusinessRuleException("TAX_ID_INVALID", "Enter a valid VKN (10 digits) or TCKN (11 digits)");
        }
        return digits;
    }

    static boolean isTckn(String value) {
        if (!value.matches("[1-9][0-9]{10}")) {
            return false;
        }
        int[] d = value.chars().map(c -> c - '0').toArray();
        int odd = d[0] + d[2] + d[4] + d[6] + d[8];
        int even = d[1] + d[3] + d[5] + d[7];
        int tenth = Math.floorMod(odd * 7 - even, 10);
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            sum += d[i];
        }
        return d[9] == tenth && d[10] == sum % 10;
    }

    static boolean isVkn(String value) {
        if (!value.matches("[0-9]{10}")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int tmp = (value.charAt(i) - '0' + 9 - i) % 10;
            sum += tmp == 9 ? 9 : (tmp * (1 << (9 - i))) % 9;
        }
        return value.charAt(9) - '0' == (10 - sum % 10) % 10;
    }

    /** What screens show: enough to recognise it, not enough to copy it. */
    static String masked(String taxId) {
        return taxId == null ? null : "•".repeat(taxId.length() - 3) + taxId.substring(taxId.length() - 3);
    }
}
