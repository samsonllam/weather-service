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
 * Serves observations from the cache while they are younger than the TTL, refreshes them from the
 * provider once they expire, and falls back to the expired entry when the provider fails.
 *
 * <p>Refreshes are single-flight per city: when an entry expires under load, one request performs
 * the provider call while the others wait for its result instead of each hitting the providers.
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
     * @param ttl      how long an observation is served without contacting the provider
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
        Optional<CachedWeather> fresh = freshEntry(city);
        if (fresh.isPresent()) {
            return WeatherReport.fresh(fresh.get());
        }
        ReentrantLock lock = refreshLocks.computeIfAbsent(city, ignored -> new ReentrantLock());
        lock.lock();
        try {
            // Another request may have refreshed the entry while this one waited for the lock.
            return freshEntry(city)
                    .map(WeatherReport::fresh)
                    .orElseGet(() -> refresh(city));
        } finally {
            lock.unlock();
        }
    }

    private Optional<CachedWeather> freshEntry(City city) {
        Instant now = clock.instant();
        return cache.get(city).filter(entry -> entry.isFreshAt(now, ttl));
    }

    private WeatherReport refresh(City city) {
        try {
            Weather weather = provider.currentWeather(city);
            CachedWeather entry = new CachedWeather(weather, clock.instant());
            cache.put(city, entry);
            return WeatherReport.fresh(entry);
        } catch (ProviderException e) {
            Optional<CachedWeather> stale = cache.get(city);
            if (stale.isPresent()) {
                log.warn("Serving stale weather for {} fetched at {}: {}", city, stale.get().fetchedAt(), e.getMessage());
                return WeatherReport.stale(stale.get());
            }
            throw new WeatherUnavailableException(city, e);
        }
    }
}
