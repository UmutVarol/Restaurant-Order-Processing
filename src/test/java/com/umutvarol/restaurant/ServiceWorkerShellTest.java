package com.umutvarol.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The service worker keeps a hand-written list of the app shell. A file missing from it would be missing on a till
 * that opens offline, so every static asset must be listed, and API paths must never be cached.
 */
class ServiceWorkerShellTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    @Test
    void everyStaticAssetIsInTheOfflineShell() throws IOException {
        String worker = Files.readString(STATIC.resolve("sw.js"));
        List<String> assets;
        try (Stream<Path> files = Files.walk(STATIC.resolve("assets"))) {
            assets = files.filter(Files::isRegularFile)
                    .map(file -> STATIC.relativize(file).toString().replace('\\', '/'))
                    .filter(name -> !name.endsWith(".txt"))
                    .toList();
        }
        assertThat(assets).isNotEmpty().allSatisfy(asset -> assertThat(worker).contains("'" + asset + "'"));
        assertThat(worker).contains("'index.html'", "'manifest.webmanifest'", "'favicon.svg'");
    }

    @Test
    void apiResponsesAreNeverCached() throws IOException {
        String worker = Files.readString(STATIC.resolve("sw.js"));
        assertThat(worker).contains("const NEVER = ['/api/'").contains("request.headers.has('Authorization')");
    }
}
