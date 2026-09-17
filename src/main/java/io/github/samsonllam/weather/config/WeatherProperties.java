package io.github.samsonllam.weather.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under {@code weather.*} in {@code application.yml}. */
@ConfigurationProperties(prefix = "weather")
public record WeatherProperties(Duration cacheTtl, Providers providers, CircuitBreakerSettings circuitBreaker) {

    public record Providers(ProviderSettings weatherstack, ProviderSettings openweathermap) {
    }

    /** @param timeout applied to both connecting and reading, per request */
    public record ProviderSettings(String baseUrl, String apiKey, Duration timeout) {
    }

    public record CircuitBreakerSettings(
            int slidingWindowSize,
            int minimumNumberOfCalls,
            float failureRateThreshold,
            Duration waitDurationInOpenState) {
    }
}
