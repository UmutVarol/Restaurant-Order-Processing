package com.umutvarol.restaurant.directory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The guest app's directory: restaurants near a guest, their public page, check-in; and where a branch is. */
@Tag(name = "Guest app", description = "Finding the restaurant a guest is in, its page and check-in. No sign-in.")
@Validated
@RestController
class DirectoryController {

    record CheckinRequest(@Nullable @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]{8,64}") String device,
                          VenueCheckin.Method method) {
    }

    record PlaceRequest(@Nullable @DecimalMin("-90") @DecimalMax("90") Double latitude,
                        @Nullable @DecimalMin("-180") @DecimalMax("180") Double longitude,
                        @Nullable @Size(max = 200) String address, boolean listed) {
    }

    private final DirectoryService directory;

    DirectoryController(DirectoryService directory) {
        this.directory = directory;
    }

    @Operation(summary = "Listed restaurants near a position", description = """
            The position is used for this search only and is not stored. `confident` is true when one place stands \\
            out, so the app can greet the guest with it; otherwise it offers the list.""")
    @SecurityRequirements
    @GetMapping("/api/v1/public/venues/nearby")
    DirectoryService.Nearby nearby(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                   @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
                                   @RequestParam(required = false) @Min(0) @Max(5000) Double accuracy) {
        return directory.nearby(lat, lng, accuracy);
    }

    @Operation(summary = "A restaurant's public page: who it is, its ratings and its menu at this branch's prices")
    @SecurityRequirements
    @GetMapping("/api/v1/public/venue/{business}/{branch}")
    DirectoryService.VenuePage page(@PathVariable @Pattern(regexp = "[a-z0-9-]{2,40}") String business,
                                    @PathVariable @Pattern(regexp = "[a-z0-9-]{2,20}") String branch) {
        return directory.page(branch);
    }

    @Operation(summary = "The restaurant's logo")
    @SecurityRequirements
    @GetMapping("/api/v1/public/venue/{business}/{branch}/logo")
    ResponseEntity<byte[]> logo(@PathVariable @Pattern(regexp = "[a-z0-9-]{2,40}") String business,
                                @PathVariable @Pattern(regexp = "[a-z0-9-]{2,20}") String branch) {
        return directory.logo(branch).map(l -> l.response()).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "The guest confirms they are here", description = """
            `device` is a random key the app keeps; only a hash of it is stored, to count returning guests.""")
    @SecurityRequirements
    @PostMapping("/api/v1/public/venue/{business}/{branch}/checkin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void checkIn(@PathVariable @Pattern(regexp = "[a-z0-9-]{2,40}") String business,
                 @PathVariable @Pattern(regexp = "[a-z0-9-]{2,20}") String branch,
                 @Valid @RequestBody CheckinRequest request) {
        directory.checkIn(branch, request.device(), request.method());
    }

    @Operation(summary = "Where each branch is, and whether guests can find it", description = "Managers.")
    @GetMapping("/api/v1/directory")
    List<DirectoryService.DirectoryEntry> entries() {
        return directory.entries();
    }

    @Operation(summary = "Place a branch in the guest app's directory", description = """
            Managers of all branches, or a branch manager for their own branch.""")
    @PutMapping("/api/v1/directory/{branchId}")
    DirectoryService.DirectoryEntry place(@PathVariable Long branchId, @Valid @RequestBody PlaceRequest request,
                                          JwtAuthenticationToken auth) {
        Object own = auth.getToken().getClaim(com.umutvarol.restaurant.business.BusinessFilter.BRANCH_CLAIM);
        if (own != null && !own.toString().equals(branchId.toString())) {
            throw new com.umutvarol.restaurant.common.error.AccessRefusedException("BRANCH_NOT_YOURS",
                    "A branch manager changes only their own branch");
        }
        return directory.place(branchId, request.latitude(), request.longitude(), request.address(), request.listed());
    }
}
