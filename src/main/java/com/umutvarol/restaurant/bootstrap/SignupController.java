package com.umutvarol.restaurant.bootstrap;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Public")
@RestController
class SignupController {

    private final SignupService signup;

    SignupController(SignupService signup) {
        this.signup = signup;
    }

    @Operation(summary = "Open a new business", description = """
            Sofra Cloud only: a 14-day trial with sample tables and dishes, and the owner signed in.""")
    @SecurityRequirements
    @PostMapping("/api/v1/public/signup")
    ResponseEntity<SignupService.SignupResponse> signUp(@Valid @RequestBody SignupService.SignupRequest request,
                                                        HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(signup.signUp(request, http.getRemoteAddr()));
    }

    @Operation(summary = "Is this address free?", description = "And a suggestion made from the business's name.")
    @SecurityRequirements
    @GetMapping("/api/v1/public/signup/address")
    SignupService.AddressCheck address(@RequestParam String name, @RequestParam(required = false) String slug) {
        return signup.checkAddress(name, slug);
    }
}
