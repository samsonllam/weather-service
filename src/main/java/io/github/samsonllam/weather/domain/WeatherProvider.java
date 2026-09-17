package io.github.samsonllam.weather.domain;

/**
 * A source of current weather observations. Implementations wrap one upstream API each and are
 * expected to fail fast with a {@link ProviderException} rather than return partial data.
 */
public interface WeatherProvider {

    /** Short, stable identifier used in logs, metrics and health output, e.g. {@code weatherstack}. */
    String name();

    /**
     * Fetches the current weather for {@code city}.
     *
     * @throws ProviderException when the provider cannot deliver a usable observation
     */
    Weather currentWeather(City city);
}
