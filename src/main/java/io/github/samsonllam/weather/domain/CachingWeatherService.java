package io.github.samsonllam.weather.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves observations from the cache and asks the providers at most once per TTL per city.
 *
 * <p>An entry younger than the TTL is served as is. Once it is older, the next request refreshes it
 * from the provider; if the provider fails, the old observation is kept, marked as checked, and
 * served as stale until the TTL has passed again. So during an outage the providers are probed once
 * per TTL and every other request is answered from memory. With nothing cached at all there is
 * nothing to serve, and the request fails.
 *
 * <p>Refreshes are single-flight per city: when an entry expires under load, one request performs
 * the provider call while the others wait for its outcome, success or failure, instead of each
 * hitting the providers.
 */
public final class CachingWeatherService implements WeatherService {

    private static final Logger log = LoggerFactory.getLogger(CachingWeatherService.class);

    private final WeatherProvider provider;
    private final WeatherCache cache;
    private final Duration ttl;
    private final Clock clock;
    private final Map<City, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();

    /**
     * @param provider the provider to refresh from, normally a {@link FailoverWeatherProvider}
     * @param ttl      how long an observation, or a failed attempt, is served without contacting the provider
     */
    public CachingWeatherService(WeatherProvider provider, WeatherCache cache, Duration ttl, Clock clock) {
        if (ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must not be negative");
        }
        this.provider = provider;
        this.cache = cache;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public WeatherReport currentWeather(City city) {
        Optional<WeatherReport> cached = reportFromCache(city);
        if (cached.isPresent()) {
            return cached.get();
        }
        ReentrantLock lock = refreshLocks.computeIfAbsent(city, ignored -> new ReentrantLock());
        lock.lock();
        try {
            // Another request may have refreshed, or failed to refresh, while this one waited for the lock.
            return reportFromCache(city).orElseGet(() -> refresh(city));
        } finally {
            lock.unlock();
        }
    }

    /** The cached entry, if the providers were asked within the last TTL; empty when they must be asked now. */
    private Optional<WeatherReport> reportFromCache(City city) {
        Instant now = clock.instant();
        return cache.get(city)
                .filter(entry -> entry.checkedWithin(ttl, now))
                .map(entry -> new WeatherReport(entry.weather(), entry.fetchedAt(), entry.olderThan(ttl, now)));
    }

    private WeatherReport refresh(City city) {
        // Stamped before the call: the observation is at least as old as the moment it was requested.
        Instant attemptedAt = clock.instant();
        try {
            Weather weather = provider.currentWeather(city);
            cache.put(city, CachedWeather.fetched(weather, attemptedAt));
            return new WeatherReport(weather, attemptedAt, false);
        } catch (RuntimeException e) {
            Optional<CachedWeather> previous = cache.get(city);
            if (previous.isEmpty()) {
                throw new WeatherUnavailableException(city, e);
            }
            CachedWeather stale = previous.get().withCheckedAt(attemptedAt);
            cache.put(city, stale);
            log.warn("Serving stale weather for {} fetched at {}, next provider attempt after {}: {}",
                    city, stale.fetchedAt(), ttl, e.getMessage());
            return new WeatherReport(stale.weather(), stale.fetchedAt(), true);
        }
    }
}
