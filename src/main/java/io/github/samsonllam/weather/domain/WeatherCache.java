package io.github.samsonllam.weather.domain;

import java.util.Optional;

/**
 * Stores the most recent observation per city. Entries are never expired by the cache itself:
 * {@link CachingWeatherService} decides what is fresh, and keeps expired entries around to serve
 * them as stale data while every provider is down.
 */
public interface WeatherCache {

    Optional<CachedWeather> get(City city);

    void put(City city, CachedWeather entry);

    /** Drops every entry: an operational flush, also used to isolate test scenarios. */
    void clear();
}
