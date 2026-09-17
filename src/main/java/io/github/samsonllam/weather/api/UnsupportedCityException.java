package io.github.samsonllam.weather.api;

import io.github.samsonllam.weather.domain.City;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** The {@code city} query parameter names a city this service does not report on. */
class UnsupportedCityException extends RuntimeException {

    private static final int MAX_ECHOED_LENGTH = 40;

    UnsupportedCityException(String city) {
        super("Unsupported city '" + abbreviate(city) + "'. Supported cities: " + Arrays.stream(City.values())
                .map(supported -> supported.displayName().toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(", ")));
    }

    private static String abbreviate(String city) {
        return city.length() <= MAX_ECHOED_LENGTH ? city : city.substring(0, MAX_ECHOED_LENGTH) + "...";
    }
}
