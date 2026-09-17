package io.github.samsonllam.weather.domain;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local cache; a shared store such as Redis would replace this when running several instances. */
public final class InMemoryWeatherCache implements WeatherCache {

    private final Map<City, CachedWeather> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<CachedWeather> get(City city) {
        return Optional.ofNullable(entries.get(city));
    }

    @Override
    public void put(City city, CachedWeather entry) {
        entries.put(city, entry);
    }

    @Override
    public void clear() {
        entries.clear();
    }
}
