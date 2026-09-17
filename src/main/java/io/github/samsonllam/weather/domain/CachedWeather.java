package io.github.samsonllam.weather.domain;

import java.time.Duration;
import java.time.Instant;

/** A cached observation and the moment it was received from a provider. */
public record CachedWeather(Weather weather, Instant fetchedAt) {

    /** True when the observation is older than {@code ttl}, i.e. it is being served stale. */
    public boolean olderThan(Duration ttl, Instant now) {
        return now.isAfter(fetchedAt.plus(ttl));
    }
}
