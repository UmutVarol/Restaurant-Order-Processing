package com.umutvarol.restaurant.directory;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Verified-visit ratings: a guest who paid rates the visit; the restaurant reads and answers. */
@Tag(name = "Ratings", description = "Ratings of paid visits, and the restaurant's answers")
@Validated
@RestController
class RatingController {

    record ReplyRequest(@Nullable @Size(max = 500) String reply) {
    }

    private final RatingService ratings;

    RatingController(RatingService ratings) {
        this.ratings = ratings;
    }

    @Operation(summary = "Whether this paid visit can be rated, and its dishes")
    @SecurityRequirements
    @GetMapping("/api/v1/public/pay/{token}/rating")
    RatingService.GuestRating forGuest(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,100}") String token) {
        return ratings.forGuest(token);
    }

    @Operation(summary = "Rate a paid visit, once", description = "Stars 1–5, optional tags, liked dishes and a comment.")
    @SecurityRequirements
    @PostMapping("/api/v1/public/pay/{token}/rating")
    RatingService.GuestRating rate(@PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{20,100}") String token,
                                   @Valid @RequestBody RatingService.RateRequest request) {
        return ratings.rate(token, request);
    }

    @Operation(summary = "Recent ratings", description = "Managers; a branch manager sees their branch's.")
    @GetMapping("/api/v1/ratings")
    List<RatingService.StaffRating> recent(@RequestParam(defaultValue = "30") int limit) {
        return ratings.recent(limit);
    }

    @Operation(summary = "Guests and ratings at a glance (panel card)")
    @GetMapping("/api/v1/ratings/summary")
    RatingService.Summary summary() {
        return ratings.summary();
    }

    @Operation(summary = "Answer a rating in public", description = "An empty answer removes it.")
    @PutMapping("/api/v1/ratings/{id}/reply")
    RatingService.StaffRating reply(@PathVariable Long id, @Valid @RequestBody ReplyRequest request, Authentication auth) {
        return ratings.reply(id, request.reply(), auth.getName());
    }
}
