package com.umutvarol.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every language of the web client says everything English says, with the same {placeholders}: a text added to
 * English alone would show up in English in the middle of a Turkish, German, French, Russian or Arabic screen.
 * Plural forms are free (Russian needs few and many, Arabic six forms, Turkish only other), as long as the
 * language has the "other" form.
 */
class LocaleFilesTest {

    private static final Path LOCALES = Path.of("src/main/resources/static/assets/js/core/locales");
    private static final Pattern ENTRY = Pattern.compile("^\\s+(['\"])(.+?)\\1:\\s(.*),$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)}");
    private static final Pattern PLURAL = Pattern.compile("\\.(zero|one|two|few|many|other)$");

    @ParameterizedTest
    @ValueSource(strings = {"tr", "de", "fr", "ru", "ar"})
    void aLanguageHasEveryEnglishTextWithTheSamePlaceholders(String language) throws IOException {
        Map<String, String> english = entries("en");
        Map<String, String> translated = entries(language);
        List<String> problems = new ArrayList<>();
        for (String key : english.keySet()) {
            String base = PLURAL.matcher(key).replaceFirst("");
            boolean pluralCovered = !base.equals(key) && translated.containsKey(base + ".other");
            if (!translated.containsKey(key) && !pluralCovered) {
                problems.add("missing " + key);
            }
        }
        for (Map.Entry<String, String> entry : translated.entrySet()) {
            String key = entry.getKey();
            String base = PLURAL.matcher(key).replaceFirst("");
            String reference = english.containsKey(key) ? english.get(key) : english.get(base + ".other");
            if (reference == null) {
                problems.add("not in English: " + key);
            } else if (!placeholders(entry.getValue()).equals(placeholders(reference))) {
                problems.add(key + " has " + placeholders(entry.getValue()) + ", English " + placeholders(reference));
            }
        }
        assertThat(problems).as(language).isEmpty();
    }

    private static Map<String, String> entries(String language) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        for (String line : Files.readAllLines(LOCALES.resolve(language + ".js"))) {
            Matcher matcher = ENTRY.matcher(line);
            if (matcher.matches()) {
                assertThat(entries.put(matcher.group(2), matcher.group(3))).as("%s twice in %s", matcher.group(2), language).isNull();
            }
        }
        assertThat(entries).as(language).hasSizeGreaterThan(900);
        return entries;
    }

    private static Set<String> placeholders(String text) {
        Set<String> names = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }
}
