package io.github.samsonllam.weather.domain;

/**
 * A weather observation in the units this service exposes: degrees Celsius and kilometres per hour.
 * Providers convert into these units so the rest of the code never has to know where a value came from.
 */
public record Weather(double temperatureCelsius, double windSpeedKph) {
}
