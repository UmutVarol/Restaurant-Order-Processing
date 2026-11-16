package com.umutvarol.restaurant.guest;

import com.umutvarol.restaurant.business.BusinessContext;
import com.umutvarol.restaurant.common.error.BusinessRuleException;
import com.umutvarol.restaurant.guest.dto.BrandingResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A business's colour and logo on its guests' screens. A logo is a PNG or WebP image of at most
 * {@value #MAX_LOGO_BYTES} bytes, checked by its first bytes and not by what the browser says it is; SVG is refused
 * because it can carry scripts.
 */
@Service
@Transactional
public class BrandingService {

    public static final int MAX_LOGO_BYTES = 60 * 1024;

    private static final Logger audit = LoggerFactory.getLogger("guest.audit");
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private final GuestBrandingRepository brandings;
    private final Clock clock;

    BrandingService(GuestBrandingRepository brandings, Clock clock) {
        this.brandings = brandings;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public BrandingResponse get() {
        return brandings.findById(business()).map(BrandingService::view).orElse(BrandingResponse.NONE);
    }

    public BrandingResponse recolour(@Nullable String accent, String actor) {
        GuestBranding branding = current();
        String colour = accent == null ? null : accent.toLowerCase(Locale.ROOT);
        branding.recolour(colour, clock.instant(), actor);
        audit.info("guest.branding accent={} by={}", colour, actor);
        return view(branding);
    }

    public BrandingResponse replaceLogo(byte[] image, String actor) {
        if (image.length == 0 || image.length > MAX_LOGO_BYTES) {
            throw new BusinessRuleException("LOGO_INVALID", "The logo must be a PNG or WebP image of at most 60 KB.");
        }
        String type = typeOf(image);
        if (type == null) {
            throw new BusinessRuleException("LOGO_INVALID", "The logo must be a PNG or WebP image of at most 60 KB.");
        }
        GuestBranding branding = current();
        branding.replaceLogo(image, type, clock.instant(), actor);
        audit.info("guest.branding logo type={} bytes={} by={}", type, image.length, actor);
        return view(branding);
    }

    public BrandingResponse removeLogo(String actor) {
        GuestBranding branding = current();
        branding.removeLogo(clock.instant(), actor);
        audit.info("guest.branding logo removed by={}", actor);
        return view(branding);
    }

    /** The current business's logo, for a guest's screen; empty when it has none. */
    @Transactional(readOnly = true)
    public Optional<Logo> logo() {
        return brandings.findById(business()).filter(GuestBranding::hasLogo)
                .map(branding -> new Logo(branding.getLogo(), branding.getLogoType()));
    }

    /** The image and its media type. */
    public record Logo(byte[] bytes, String type) {

        /**
         * The image for a guest's browser. Its address carries the logo's number, so it can be kept for a day;
         * {@code nosniff} and the fixed type keep the browser from reading it as anything but an image.
         */
        public ResponseEntity<byte[]> response() {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(type))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; sandbox")
                    .body(bytes.clone());
        }
    }

    static @Nullable String typeOf(byte[] image) {
        if (image.length >= PNG.length && Arrays.equals(Arrays.copyOf(image, PNG.length), PNG)) {
            return "image/png";
        }
        boolean riff = image.length >= 12 && image[0] == 'R' && image[1] == 'I' && image[2] == 'F' && image[3] == 'F';
        if (riff && image[8] == 'W' && image[9] == 'E' && image[10] == 'B' && image[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static BrandingResponse view(GuestBranding branding) {
        return new BrandingResponse(branding.getAccent(), branding.hasLogo() ? branding.getLogoVersion() : null);
    }

    private GuestBranding current() {
        long business = business();
        return brandings.findById(business).orElseGet(() -> brandings.save(GuestBranding.none(business)));
    }

    private static long business() {
        return Optional.ofNullable(BusinessContext.current()).orElse(1L);
    }
}
