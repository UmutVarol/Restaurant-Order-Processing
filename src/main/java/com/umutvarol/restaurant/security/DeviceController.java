package com.umutvarol.restaurant.security;

import com.umutvarol.restaurant.security.dto.DeviceRegistration;
import com.umutvarol.restaurant.security.dto.DeviceRequest;
import com.umutvarol.restaurant.security.dto.DeviceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Devices", description = "Tills registered for PIN sign-in")
@RestController
@RequestMapping("/api/v1/devices")
class DeviceController {

    private final StaffPinService pins;
    private final StepUpAuthentication stepUp;

    DeviceController(StaffPinService pins, StepUpAuthentication stepUp) {
        this.pins = pins;
        this.stepUp = stepUp;
    }

    @Operation(summary = "Registered devices")
    @GetMapping
    List<DeviceResponse> devices() {
        return pins.devices();
    }

    @Operation(summary = "Register this device", description = """
            Returns the device token once; the till keeps it and sends it as `X-Device-Token`. Needs a recent
            password sign-in.""")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DeviceRegistration register(@Valid @RequestBody DeviceRequest request, Authentication authentication) {
        stepUp.requireRecentSignIn(authentication);
        return pins.register(request.name(), authentication.getName());
    }

    @Operation(summary = "Revoke a device", description = "PIN sign-in stops working on it at once.")
    @DeleteMapping("/{id}")
    DeviceResponse revoke(@PathVariable long id, Authentication authentication) {
        stepUp.requireRecentSignIn(authentication);
        return pins.revoke(id, authentication.getName());
    }
}
