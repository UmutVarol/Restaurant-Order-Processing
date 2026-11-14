package com.umutvarol.restaurant.bootstrap;

import io.swagger.v3.oas.annotations.Hidden;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Digital Asset Links for the Google Play app (a Trusted Web Activity, docs/CLOUD.md): it proves that the app and
 * this site belong together, so the app shows the site full screen without a browser bar. Served only when
 * {@code restaurant.stores.android-sha256} holds the app's signing certificate fingerprint(s).
 */
@Hidden
@RestController
class StoreLinksController {

    private final String packageName;
    private final List<String> fingerprints;

    StoreLinksController(@Value("${restaurant.stores.android-package:app.sofra.restaurant}") String packageName,
                         @Value("${restaurant.stores.android-sha256:}") String fingerprints) {
        this.packageName = packageName;
        this.fingerprints = Arrays.stream(fingerprints.split(",")).map(String::strip).filter(f -> !f.isEmpty()).toList();
    }

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<List<Map<String, Object>>> assetLinks() {
        if (fingerprints.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(List.of(Map.of(
                "relation", List.of("delegate_permission/common.handle_all_urls"),
                "target", Map.of("namespace", "android_app", "package_name", packageName,
                        "sha256_cert_fingerprints", fingerprints))));
    }
}
