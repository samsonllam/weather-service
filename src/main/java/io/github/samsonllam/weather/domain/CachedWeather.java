package io.github.samsonllam.weather.domain;

import java.time.Duration;
import java.time.Instant;

/** A weather observation together with the moment it was fetched from a provider. */
public record CachedWeather(Weather weather, Instant fetchedAt) {

    /** Fresh while the entry is at most {@code ttl} old, so a 3 s TTL still serves an entry aged exactly 3 s. */
    public boolean isFreshAt(Instant now, Duration ttl) {
        return !now.isAfter(fetchedAt.plus(ttl));
    }
}
