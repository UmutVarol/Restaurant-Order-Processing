package com.umutvarol.restaurant.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Hibernate limits every JPQL and criteria query to the current business (docs/adr/0001-multi-business.md). Native
 * SQL and JdbcTemplate bypass that, so production code uses neither; database migrations are the one exception.
 */
class NoUnfilteredSqlTest {

    @Test
    void productionCodeHasNoSqlThatSkipsTheBusinessFilter() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/umutvarol/restaurant"))) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            String code = Files.readString(p);
                            return code.contains("nativeQuery = true") || code.contains("createNativeQuery")
                                    || code.contains("JdbcTemplate") || code.contains("@NativeQuery");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
            assertThat(offenders).as("SQL that is not limited to the current business").isEmpty();
        }
    }
}
