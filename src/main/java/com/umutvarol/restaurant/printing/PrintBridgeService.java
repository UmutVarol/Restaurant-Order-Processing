package com.umutvarol.restaurant.printing;

import com.umutvarol.restaurant.common.crypto.SecretTokens;
import com.umutvarol.restaurant.common.error.ResourceNotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Print bridges of a business: managers add one per branch (its token is shown once) and switch off one that was
 * lost; a bridge signs in with its token on every call.
 */
@Service
@Transactional
public class PrintBridgeService {

    private static final Logger audit = LoggerFactory.getLogger("printing.audit");
    /** A bridge asks every few seconds; its last visit is written at most this often. */
    private static final Duration SEEN_EVERY = Duration.ofSeconds(30);

    public record BridgeView(Long id, String name, Long branchId, Instant createdAt, @Nullable Instant lastSeenAt) {
        static BridgeView of(PrintBridge bridge) {
            return new BridgeView(bridge.getId(), bridge.getName(), bridge.getBranchId(), bridge.getCreatedAt(), bridge.getLastSeenAt());
        }
    }

    public record NewBridge(BridgeView bridge, String token) {
    }

    private final PrintBridgeRepository bridges;
    private final Clock clock;

    PrintBridgeService(PrintBridgeRepository bridges, Clock clock) {
        this.bridges = bridges;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BridgeView> list() {
        return bridges.findByRevokedAtIsNullOrderByIdAsc().stream().map(BridgeView::of).toList();
    }

    /** A new bridge for the branch the request works in; the token is returned this once. */
    public NewBridge create(String name, String actor) {
        String token = SecretTokens.newToken();
        PrintBridge bridge = bridges.save(new PrintBridge(name.strip(), SecretTokens.hash(token), clock.instant(), actor));
        audit.info("printing.bridge_added id={} branch={} by={}", bridge.getId(), bridge.getBranchId(), actor);
        return new NewBridge(BridgeView.of(bridge), token);
    }

    public void revoke(Long id, String actor) {
        PrintBridge bridge = bridges.findById(id).filter(PrintBridge::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("print_bridge", id));
        bridge.revoke(clock.instant());
        audit.info("printing.bridge_revoked id={} by={}", id, actor);
    }

    /** The bridge a token belongs to (within the business the token located); a revoked one is refused. */
    public PrintBridge authenticate(@Nullable String token) {
        // an unknown token located no business: refused before anything is looked up
        if (token == null || token.isBlank() || token.length() > 200 || com.umutvarol.restaurant.business.BusinessContext.current() == null) {
            throw new BadCredentialsException("No print bridge token");
        }
        PrintBridge bridge = bridges.findByTokenHash(SecretTokens.hash(token)).filter(PrintBridge::isActive)
                .orElseThrow(() -> new BadCredentialsException("Unknown print bridge"));
        Instant now = clock.instant();
        if (bridge.getLastSeenAt() == null || bridge.getLastSeenAt().isBefore(now.minus(SEEN_EVERY))) {
            bridge.seen(now);
        }
        return bridge;
    }
}
