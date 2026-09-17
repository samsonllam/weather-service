package io.github.samsonllam.weather.domain;

/**
 * A weather observation in the units this service exposes: degrees Celsius and kilometres per hour.
 * Providers convert into these units so the rest of the code never has to know where a value came from.
 *
 * <p>Values outside the physically plausible range are rejected, so a provider glitch cannot become
 * the value that is served for the whole of an outage.
 */
public record Weather(double temperatureCelsius, double windSpeedKph) {

    static final double MIN_TEMPERATURE = -100;
    static final double MAX_TEMPERATURE = 70;
    static final double MAX_WIND_SPEED = 500;

    public Weather {
        if (!Double.isFinite(temperatureCelsius) || temperatureCelsius < MIN_TEMPERATURE || temperatureCelsius > MAX_TEMPERATURE) {
            throw new IllegalArgumentException("implausible temperature: " + temperatureCelsius + " C");
        }
        if (!Double.isFinite(windSpeedKph) || windSpeedKph < 0 || windSpeedKph > MAX_WIND_SPEED) {
            throw new IllegalArgumentException("implausible wind speed: " + windSpeedKph + " km/h");
        }
    }
}
