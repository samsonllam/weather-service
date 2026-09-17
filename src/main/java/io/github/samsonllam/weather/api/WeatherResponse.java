package io.github.samsonllam.weather.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.samsonllam.weather.domain.Weather;

/**
 * The public payload of {@code GET /v1/weather}: wind speed in km/h and temperature in degrees
 * Celsius, both rounded to whole numbers as in the specification's example.
 */
public record WeatherResponse(
        @JsonProperty("wind_speed") long windSpeed,
        @JsonProperty("temperature_degrees") long temperatureDegrees) {

    static WeatherResponse from(Weather weather) {
        return new WeatherResponse(Math.round(weather.windSpeedKph()), Math.round(weather.temperatureCelsius()));
    }
}
