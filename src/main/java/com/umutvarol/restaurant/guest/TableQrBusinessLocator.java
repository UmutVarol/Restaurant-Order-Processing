package com.umutvarol.restaurant.guest;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.business.BusinessLocator;
import com.umutvarol.restaurant.common.crypto.SecretTokens;
import com.umutvarol.restaurant.table.DiningTable;
import com.umutvarol.restaurant.table.DiningTableRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** A guest at a table belongs to the business whose table's QR code they scanned. */
@Component
class TableQrBusinessLocator implements BusinessLocator {

    private static final Pattern PATH = Pattern.compile("^/api/v1/public/table/([A-Za-z0-9_-]{20,100})(/.*)?$");

    private final DiningTableRepository tables;

    TableQrBusinessLocator(DiningTableRepository tables) {
        this.tables = tables;
    }

    @Override
    public Optional<Long> locate(HttpServletRequest request) {
        Matcher match = PATH.matcher(request.getRequestURI());
        if (!match.matches()) {
            return Optional.empty();
        }
        String hash = SecretTokens.hash(match.group(1));
        return BusinessContext.acrossAll(() -> tables.findByQrTokenHash(hash).filter(DiningTable::isActive).map(DiningTable::getBusinessId));
    }
}
