package io.github.samsonllam.weather.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves observations from the cache and asks the providers at most once per TTL per city.
 *
 * <p>The outcome of every refresh attempt, success or failure, is recorded with its completion
 * time. While the last attempt is younger than the TTL, requests are answered from memory: the
 * cached observation (flagged stale if it is older than the TTL, which only happens after a failed
 * refresh), or an error if the attempt failed and nothing was ever cached. Once the last attempt is
 * older than the TTL, the next request asks the providers again. So during an outage the providers
 * are probed once per TTL and every other request is answered from memory, with or without a value
 * to show for it.
 *
 * <p>Refreshes are single-flight per city: when an entry expires under load, one request performs
 * the provider call while the others wait for its outcome. A waiter gives up after one TTL and
 * takes the last known value instead, so a slow refresh never queues customers behind it.
 *
 * <p>The cache holds observations only; the lock and the attempt record live in this instance. Running
 * several instances multiplies the probes by the number of instances, which is acceptable for
 * weather data; a cluster-wide single-flight would need a shared coordinator, not just a shared cache.
 */
public final class CachingWeatherService implements WeatherService {

    private static final Logger log = LoggerFactory.getLogger(CachingWeatherService.class);

    private final WeatherProvider provider;
    private final WeatherCache cache;
    private final Duration ttl;
    private final Clock clock;
    private final Map<City, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();
    private final Map<City, Attempt> attempts = new ConcurrentHashMap<>();

    /** The last refresh attempt for a city; {@code failure} is null when it succeeded. */
    private record Attempt(Instant completedAt, RuntimeException failure) {

        boolean within(Duration ttl, Instant now) {
            return !now.isAfter(completedAt.plus(ttl));
        }
    }

    /**
     * @param provider the provider to refresh from, normally a {@link FailoverWeatherProvider}
     * @param ttl      how long the outcome of a refresh attempt is served without contacting the provider
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
        Optional<WeatherReport> answered = withoutAskingProviders(city);
        if (answered.isPresent()) {
            return answered.get();
        }
        ReentrantLock lock = refreshLocks.computeIfAbsent(city, ignored -> new ReentrantLock());
        if (!acquireWithinTtl(lock)) {
            return lastKnown(city);
        }
        try {
            // Another request may have completed an attempt while this one waited for the lock.
            return withoutAskingProviders(city).orElseGet(() -> refresh(city));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Answers from memory while the last attempt is younger than the TTL; empty when the providers
     * must be asked now.
     *
     * @throws WeatherUnavailableException when the last attempt failed and nothing was ever cached
     */
    private Optional<WeatherReport> withoutAskingProviders(City city) {
        Instant now = clock.instant();
        Attempt last = attempts.get(city);
        if (last == null || !last.within(ttl, now)) {
            return Optional.empty();
        }
        CachedWeather entry = cache.get(city)
                .orElseThrow(() -> new WeatherUnavailableException(city, last.failure()));
        return Optional.of(report(entry, now));
    }

    private boolean acquireWithinTtl(ReentrantLock lock) {
        try {
            return lock.tryLock(ttl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** For a request that waited one TTL for another request's refresh: the last known value, or nothing. */
    private WeatherReport lastKnown(City city) {
        Instant now = clock.instant();
        return cache.get(city)
                .map(entry -> report(entry, now))
                .orElseThrow(() -> new WeatherUnavailableException(city,
                        new IllegalStateException("a refresh has been running for longer than " + ttl)));
    }

    private WeatherReport refresh(City city) {
        Weather weather;
        try {
            weather = provider.currentWeather(city);
        } catch (RuntimeException e) {
            return afterFailedRefresh(city, e);
        }
        Instant now = clock.instant();
        cache.put(city, new CachedWeather(weather, now));
        attempts.put(city, new Attempt(now, null));
        return new WeatherReport(weather, now, false);
    }

    private WeatherReport afterFailedRefresh(City city, RuntimeException failure) {
        Instant now = clock.instant();
        attempts.put(city, new Attempt(now, failure));
        CachedWeather previous = cache.get(city)
                .orElseThrow(() -> new WeatherUnavailableException(city, failure));
        log.warn("Serving stale weather for {} fetched at {}, next provider attempt after {}: {}",
                city, previous.fetchedAt(), ttl, failure.getMessage());
        return report(previous, now);
    }

    private WeatherReport report(CachedWeather entry, Instant now) {
        return new WeatherReport(entry.weather(), entry.fetchedAt(), entry.olderThan(ttl, now));
    }
}
