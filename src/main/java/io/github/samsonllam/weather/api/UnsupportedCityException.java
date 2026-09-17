package io.github.samsonllam.weather.api;

import io.github.samsonllam.weather.domain.City;
import java.util.Arrays;
import java.util.stream.Collectors;

/** The {@code city} query parameter names a city this service does not report on. */
class UnsupportedCityException extends RuntimeException {

    UnsupportedCityException(String city) {
        super("Unsupported city '" + city + "'. Supported cities: " + Arrays.stream(City.values())
                .map(supported -> supported.displayName().toLowerCase())
                .collect(Collectors.joining(", ")));
    }
}
