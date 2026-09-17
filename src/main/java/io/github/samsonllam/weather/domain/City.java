package io.github.samsonllam.weather.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Cities this service can report on. The assignment hard-codes Singapore; adding a city is a
 * matter of adding a constant here, provided every provider can resolve it.
 */
public enum City {
    SINGAPORE("Singapore", "SG");

    private final String displayName;
    private final String countryCode;

    City(String displayName, String countryCode) {
        this.displayName = displayName;
        this.countryCode = countryCode;
    }

    /** Name understood by the weather providers, e.g. {@code Singapore}. */
    public String displayName() {
        return displayName;
    }

    /** ISO 3166-1 alpha-2 country code, e.g. {@code SG}. */
    public String countryCode() {
        return countryCode;
    }

    /** Resolves a query-string value such as {@code singapore}, ignoring case and surrounding whitespace. */
    public static Optional<City> fromQuery(String query) {
        if (query == null) {
            return Optional.empty();
        }
        String wanted = query.strip();
        return Arrays.stream(values())
                .filter(city -> city.displayName.equalsIgnoreCase(wanted))
                .findFirst();
    }
}
