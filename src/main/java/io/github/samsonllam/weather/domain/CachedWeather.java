package io.github.samsonllam.weather.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * A cached observation and two timestamps: when the value was obtained from a provider
 * ({@code fetchedAt}) and when the providers were last asked, successfully or not ({@code checkedAt}).
 * The second one lets the service ask the providers at most once per TTL during an outage.
 */
public record CachedWeather(Weather weather, Instant fetchedAt, Instant checkedAt) {

    public CachedWeather {
        if (checkedAt.isBefore(fetchedAt)) {
            throw new IllegalArgumentException("checkedAt must not be before fetchedAt");
        }
    }

    /** An entry just obtained from a provider. */
    public static CachedWeather fetched(Weather weather, Instant at) {
        return new CachedWeather(weather, at, at);
    }

    /** The same observation, with a failed refresh attempt recorded at {@code at}. */
    public CachedWeather withCheckedAt(Instant at) {
        return new CachedWeather(weather, fetchedAt, at);
    }

    /** True while the last provider attempt is at most {@code ttl} old, so the providers need not be asked. */
    public boolean checkedWithin(Duration ttl, Instant now) {
        return !now.isAfter(checkedAt.plus(ttl));
    }

    /** True when the observation itself is older than {@code ttl}, i.e. it is being served stale. */
    public boolean olderThan(Duration ttl, Instant now) {
        return now.isAfter(fetchedAt.plus(ttl));
    }
}
