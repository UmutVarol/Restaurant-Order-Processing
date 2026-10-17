package com.umutvarol.restaurant.order;

import jakarta.persistence.criteria.JoinType;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.domain.Specification;

/** Composable query criteria for searching orders. Each factory returns a no-op spec for empty input. */
final class OrderSpecifications {

    private OrderSpecifications() {
    }

    static Specification<Order> hasStatusIn(@Nullable Collection<OrderStatus> statuses) {
        return (root, query, cb) -> statuses == null || statuses.isEmpty()
                ? cb.conjunction()
                : root.get("status").in(statuses);
    }

    static Specification<Order> hasType(@Nullable OrderType type) {
        return (root, query, cb) -> type == null ? cb.conjunction() : cb.equal(root.get("type"), type);
    }

    static Specification<Order> createdBetween(@Nullable Instant from, @Nullable Instant to) {
        return (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (from != null) {
                predicate = cb.and(predicate, cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicate = cb.and(predicate, cb.lessThan(root.get("createdAt"), to));
            }
            return predicate;
        };
    }

    /**
     * Free-text search: matches the order number ({@code 1042} or {@code #1042}), the customer's name or
     * phone, the delivery address or the table code.
     */
    static Specification<Order> matchesText(@Nullable String text) {
        return (root, query, cb) -> {
            if (text == null || text.isBlank()) {
                return cb.conjunction();
            }
            String needle = text.strip().toLowerCase(Locale.ROOT);
            String pattern = "%" + needle.replace("%", "\\%").replace("_", "\\_") + "%";
            var table = root.join("table", JoinType.LEFT);
            var predicate = cb.or(
                    cb.like(cb.lower(root.get("customerName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("customerPhone")), pattern, '\\'),
                    cb.like(cb.lower(root.get("deliveryAddress")), pattern, '\\'),
                    cb.like(cb.lower(table.get("code")), pattern, '\\'));
            String digits = needle.startsWith("#") ? needle.substring(1) : needle;
            if (!digits.isEmpty() && digits.length() < 18 && digits.chars().allMatch(Character::isDigit)) {
                predicate = cb.or(predicate, cb.equal(root.get("id"), Long.parseLong(digits)));
            }
            return predicate;
        };
    }
}
