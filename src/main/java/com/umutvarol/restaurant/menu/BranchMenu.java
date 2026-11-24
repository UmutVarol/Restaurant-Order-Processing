package com.umutvarol.restaurant.menu;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One branch's view of the central menu (Chain plan): its own price where it has one, its own sold-out state where
 * it has one, the central menu everywhere else. The till, a table's QR menu, a platform order and the bill all price
 * a dish through here, so what a guest sees is what they pay.
 */
@Component
public class BranchMenu {

    private final BranchMenuItemRepository overrides;

    BranchMenu(BranchMenuItemRepository overrides) {
        this.overrides = overrides;
    }

    /** The branch's differences by dish id; none for no branch (the central menu). */
    @Transactional(readOnly = true)
    public Map<Long, BranchMenuItem> of(@Nullable Long branchId) {
        if (branchId == null) {
            return Map.of();
        }
        return overrides.findByBranchId(branchId).stream().collect(Collectors.toMap(BranchMenuItem::getMenuItemId, Function.identity()));
    }

    public static BigDecimal price(MenuItem item, @Nullable BranchMenuItem branch) {
        return branch != null && branch.getPrice() != null ? branch.getPrice() : item.getPrice();
    }

    /** When the price this branch charges last changed: its own price's change, or the central one, whichever is later. */
    public static java.time.@Nullable Instant priceChangedAt(MenuItem item, @Nullable BranchMenuItem branch) {
        java.time.Instant central = item.getPriceChangedAt();
        java.time.Instant own = branch == null ? null : branch.getPriceChangedAt();
        if (own == null) {
            return central;
        }
        return central == null || own.isAfter(central) ? own : central;
    }

    /** Can be ordered in this branch: the branch's own sale state if it set one, else the central one. Stock always counts. */
    public static boolean orderable(MenuItem item, @Nullable BranchMenuItem branch) {
        if (branch == null || branch.getAvailable() == null) {
            return item.isOrderable();
        }
        return branch.getAvailable() && !item.isArchived() && !item.isStockOut();
    }
}
