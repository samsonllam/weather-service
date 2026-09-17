package io.github.samsonllam.weather.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.WeatherCache;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes each provider's circuit-breaker state and whether a cached observation exists per city
 * under {@code /actuator/health}.
 *
 * <p>The service stays {@code UP} even when every circuit is open, because it keeps answering from
 * the cache; the details are there for dashboards and alerts, not for load balancers. A circuit
 * state is the breaker's view of recent calls, not a live probe: a fallback provider's breaker can
 * stay open after the upstream recovered simply because the primary is healthy and it is never called.
 */
@Component
class WeatherProvidersHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry circuitBreakers;
    private final WeatherCache cache;

    WeatherProvidersHealthIndicator(CircuitBreakerRegistry circuitBreakers, WeatherCache cache) {
        this.circuitBreakers = circuitBreakers;
        this.cache = cache;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up();
        for (CircuitBreaker circuitBreaker : circuitBreakers.getAllCircuitBreakers()) {
            health.withDetail(circuitBreaker.getName(), circuitBreaker.getState().name());
        }
        Map<String, String> cached = new LinkedHashMap<>();
        for (City city : City.values()) {
            cached.put(city.displayName().toLowerCase(Locale.ROOT), cache.get(city).isPresent() ? "cached" : "empty");
        }
        return health.withDetail("cache", cached).build();
    }
}
