package io.github.samsonllam.weather.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under {@code weather.*} in {@code application.yml}. Provider keys are checked in {@link WeatherConfiguration}. */
@ConfigurationProperties(prefix = "weather")
public record WeatherProperties(Duration cacheTtl, Providers providers, CircuitBreakerSettings circuitBreaker) {

    public WeatherProperties {
        Objects.requireNonNull(cacheTtl, "weather.cache-ttl must be set");
        Objects.requireNonNull(providers, "weather.providers must be set");
        Objects.requireNonNull(circuitBreaker, "weather.circuit-breaker must be set");
    }

    public record Providers(ProviderSettings weatherstack, ProviderSettings openweathermap) {
    }

    /** @param timeout applied separately to connecting and to reading, per request */
    public record ProviderSettings(String baseUrl, String apiKey, Duration timeout) {
    }

    public record CircuitBreakerSettings(
            int slidingWindowSize,
            int minimumNumberOfCalls,
            float failureRateThreshold,
            Duration waitDurationInOpenState,
            int permittedCallsInHalfOpenState) {
    }
}
