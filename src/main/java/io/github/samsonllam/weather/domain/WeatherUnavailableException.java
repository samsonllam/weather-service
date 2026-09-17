package io.github.samsonllam.weather.domain;

/** Every provider is down and there is no cached observation to fall back on. */
public class WeatherUnavailableException extends RuntimeException {

    public WeatherUnavailableException(City city, Throwable cause) {
        super("no weather available for " + city, cause);
    }
}
