package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.security.dto.StaffCredentials;
import com.umutvarol.restaurant.security.dto.StaffRequest;
import com.umutvarol.restaurant.security.dto.StaffResponse;
import com.umutvarol.restaurant.security.dto.StaffUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Staff", description = "Managers add, change and switch off staff accounts")
@RestController
@RequestMapping("/api/v1/staff")
class StaffController {

    private final StaffService staff;

    StaffController(StaffService staff) {
        this.staff = staff;
    }

    @Operation(summary = "Staff accounts", description = "Active accounts first.")
    @GetMapping
    List<StaffResponse> list() {
        return staff.list();
    }

    @Operation(summary = "Add a staff account", description = """
            Returns a temporary password once; the employee chooses their own at the first sign-in. Adding a
            manager needs a recent password sign-in.""")
    @PostMapping
    ResponseEntity<StaffCredentials> create(@Valid @RequestBody StaffRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(staff.create(request, authentication));
    }

    @Operation(summary = "Change a staff account", description = "A new role or switching it off ends its sessions at once.")
    @PutMapping("/{id}")
    StaffResponse update(@PathVariable Long id, @Valid @RequestBody StaffUpdateRequest request,
                         Authentication authentication) {
        return staff.update(id, request, authentication);
    }

    @Operation(summary = "Reset a password", description = "Returns a temporary password once; needs a recent password sign-in.")
    @PostMapping("/{id}/reset-password")
    ResponseEntity<StaffCredentials> resetPassword(@PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(staff.resetPassword(id, authentication));
    }

    @Operation(summary = "Clear a PIN", description = "For a lost or shared PIN; the employee sets a new one.")
    @DeleteMapping("/{id}/pin")
    StaffResponse clearPin(@PathVariable Long id, Authentication authentication) {
        return staff.clearPin(id, authentication);
    }
}
