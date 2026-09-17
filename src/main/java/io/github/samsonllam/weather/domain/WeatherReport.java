package io.github.samsonllam.weather.domain;

import java.time.Instant;

/**
 * What the service hands to the API layer: the observation, when it was fetched, and whether it is
 * older than the cache TTL because every provider was down when a refresh was due.
 */
public record WeatherReport(Weather weather, Instant fetchedAt, boolean stale) {
}
