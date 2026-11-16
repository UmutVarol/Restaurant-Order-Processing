package com.umutvarol.restaurant.push;

import com.umutvarol.restaurant.business.BusinessFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A device asks to be told of new orders, ready plates or guests calling, by Web Push. */
@Tag(name = "Notifications", description = "Push notifications to staff devices")
@RestController
class PushController {

    record Keys(@NotBlank @Size(max = 120) String p256dh, @NotBlank @Size(max = 40) String auth) {
    }

    record SubscribeRequest(@NotBlank @Size(max = 1000) String endpoint, @NotNull @Valid Keys keys,
                            @NotNull @Size(max = 3) Set<PushTopic> topics, @Size(max = 5) String lang) {
    }

    record EndpointRequest(@NotBlank @Size(max = 1000) String endpoint) {
    }

    private final PushService push;

    PushController(PushService push) {
        this.push = push;
    }

    record NativeRequest(@NotBlank @Size(max = 900) String token, @NotNull @Size(max = 3) Set<PushTopic> topics, @Size(max = 5) String lang) {
    }

    @Operation(summary = "The server's push key", description = """
            What browsers subscribe with (applicationServerKey); native: whether the store apps can be told too.""")
    @GetMapping("/api/v1/push/key")
    Map<String, Object> key() {
        return Map.of("publicKey", push.publicKey(), "native", push.nativeEnabled());
    }

    @Operation(summary = "Get notifications in the store app on this phone", description = "By its Firebase token.")
    @PostMapping("/api/v1/push/native")
    @ResponseStatus(HttpStatus.CREATED)
    PushService.Device subscribeNative(@Valid @RequestBody NativeRequest request, JwtAuthenticationToken auth) {
        Object branch = auth.getToken().getClaim(BusinessFilter.BRANCH_CLAIM);
        return push.subscribeNative(request.token(), request.topics(), request.lang() == null ? "en" : request.lang(), auth.getName(),
                branch == null ? null : Long.valueOf(branch.toString()));
    }

    @Operation(summary = "My devices that get notifications")
    @GetMapping("/api/v1/push/subscriptions")
    List<PushService.Device> mine(JwtAuthenticationToken auth) {
        return push.devices(auth.getName());
    }

    @Operation(summary = "Get notifications on this device")
    @PostMapping("/api/v1/push/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    PushService.Device subscribe(@Valid @RequestBody SubscribeRequest request, JwtAuthenticationToken auth) {
        Object branch = auth.getToken().getClaim(BusinessFilter.BRANCH_CLAIM);
        Long branchId = branch == null ? null : Long.valueOf(branch.toString());
        return push.subscribe(new PushService.Subscribe(request.endpoint(), request.keys().p256dh(), request.keys().auth(),
                request.topics(), request.lang() == null ? "en" : request.lang()), auth.getName(), branchId);
    }

    @Operation(summary = "Stop notifications on this device")
    @PostMapping("/api/v1/push/subscriptions/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribe(@Valid @RequestBody EndpointRequest request, JwtAuthenticationToken auth) {
        push.unsubscribe(request.endpoint(), auth.getName());
    }

    @Operation(summary = "Send myself a test notification", description = "Returns how many of my devices were tried.")
    @PostMapping("/api/v1/push/test")
    Map<String, Integer> test(JwtAuthenticationToken auth) {
        return Map.of("devices", push.test(auth.getName()));
    }
}
