package io.github.samsonllam.weather.provider;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Exposes each provider's circuit-breaker state under {@code /actuator/health}.
 *
 * <p>The service stays {@code UP} even when every circuit is open, because it keeps answering from
 * the cache; the details are there for dashboards and alerts, not for load balancers.
 */
@Component
class WeatherProvidersHealthIndicator implements HealthIndicator {

    private final CircuitBreakerRegistry circuitBreakers;

    WeatherProvidersHealthIndicator(CircuitBreakerRegistry circuitBreakers) {
        this.circuitBreakers = circuitBreakers;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up();
        for (CircuitBreaker circuitBreaker : circuitBreakers.getAllCircuitBreakers()) {
            health.withDetail(circuitBreaker.getName(), circuitBreaker.getState().name());
        }
        return health.build();
    }
}
