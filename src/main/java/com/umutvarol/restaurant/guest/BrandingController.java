package com.umutvarol.restaurant.guest;

import com.umutvarol.restaurant.guest.dto.BrandingRequest;
import com.umutvarol.restaurant.guest.dto.BrandingResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Guest branding", description = "The restaurant's own colour and logo on its guests' screens")
@RestController
@RequestMapping("/api/v1/branding")
class BrandingController {

    private final BrandingService branding;

    BrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @Operation(summary = "The colour and logo guests see")
    @GetMapping
    BrandingResponse get() {
        return branding.get();
    }

    @Operation(summary = "The logo guests see", description = "404 when there is none.")
    @GetMapping("/logo")
    ResponseEntity<byte[]> logo() {
        return branding.logo().map(BrandingService.Logo::response).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Change the colour guests see", description = "Managers only. Null goes back to Sofra's colour.")
    @PutMapping
    BrandingResponse recolour(@Valid @RequestBody BrandingRequest request, Authentication authentication) {
        return branding.recolour(request.accent(), authentication.getName());
    }

    @Operation(summary = "Replace the logo", description = "Managers only. The body is a PNG or WebP image of at most 60 KB.")
    @PutMapping(value = "/logo", consumes = {"image/png", "image/webp"})
    BrandingResponse replaceLogo(@RequestBody byte[] image, Authentication authentication) {
        return branding.replaceLogo(image, authentication.getName());
    }

    @Operation(summary = "Remove the logo", description = "Managers only.")
    @DeleteMapping("/logo")
    BrandingResponse removeLogo(Authentication authentication) {
        return branding.removeLogo(authentication.getName());
    }
}
