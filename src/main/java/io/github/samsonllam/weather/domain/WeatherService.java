package io.github.samsonllam.weather.domain;

/** The use case behind {@code GET /v1/weather}. */
public interface WeatherService {

    /**
     * @throws WeatherUnavailableException when no provider can answer and nothing is cached
     */
    WeatherReport currentWeather(City city);
}
