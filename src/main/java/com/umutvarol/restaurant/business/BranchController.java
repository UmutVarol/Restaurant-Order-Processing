package com.umutvarol.restaurant.business;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A chain's branches. Everyone sees the branches they may work in; managers who work in all branches open, rename and
 * close them. A closed branch keeps its history; the last open branch cannot be closed.
 */
@Tag(name = "Branches", description = "The places of a chain")
@RestController
class BranchController {

    record BranchRequest(@NotBlank @Pattern(regexp = "^[a-z0-9-]{2,20}$") String code, @NotBlank @Size(max = 80) String name) {
    }

    record BranchUpdate(@NotBlank @Size(max = 80) String name, boolean active) {
    }

    record BranchResponse(Long id, String code, String name, boolean active) {
        static BranchResponse of(Branch b) {
            return new BranchResponse(b.getId(), b.getCode(), b.getName(), b.isActive());
        }
    }

    private final BranchService branches;

    BranchController(BranchService branches) {
        this.branches = branches;
    }

    @Operation(summary = "Branches", description = "The ones the signed-in person may work in.")
    @GetMapping("/api/v1/branches")
    List<BranchResponse> list(JwtAuthenticationToken auth) {
        return branches.visibleTo(home(auth)).stream().map(BranchResponse::of).toList();
    }

    @Operation(summary = "Open a branch", description = "Managers who work in all branches.")
    @PostMapping("/api/v1/branches")
    @ResponseStatus(HttpStatus.CREATED)
    BranchResponse open(@Valid @RequestBody BranchRequest request, JwtAuthenticationToken auth) {
        return BranchResponse.of(branches.open(request.code(), request.name(), home(auth), auth.getName()));
    }

    @Operation(summary = "Rename or close a branch", description = "Managers who work in all branches.")
    @PutMapping("/api/v1/branches/{id}")
    BranchResponse update(@PathVariable Long id, @Valid @RequestBody BranchUpdate request, JwtAuthenticationToken auth) {
        return BranchResponse.of(branches.update(id, request.name(), request.active(), home(auth), auth.getName()));
    }

    private static @Nullable Long home(JwtAuthenticationToken auth) {
        return auth.getToken().getClaim(BusinessFilter.BRANCH_CLAIM) instanceof Number id ? id.longValue() : null;
    }
}
