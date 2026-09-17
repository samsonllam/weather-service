package io.github.samsonllam.weather.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.samsonllam.weather.config.WeatherProperties.ProviderSettings;
import io.github.samsonllam.weather.domain.CachingWeatherService;
import io.github.samsonllam.weather.domain.FailoverWeatherProvider;
import io.github.samsonllam.weather.domain.InMemoryWeatherCache;
import io.github.samsonllam.weather.domain.WeatherCache;
import io.github.samsonllam.weather.domain.WeatherProvider;
import io.github.samsonllam.weather.domain.WeatherService;
import io.github.samsonllam.weather.provider.CircuitBreakingWeatherProvider;
import io.github.samsonllam.weather.provider.openweathermap.OpenWeatherMapProvider;
import io.github.samsonllam.weather.provider.weatherstack.WeatherstackProvider;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

/**
 * Wires the weather pipeline: {@code GET /v1/weather} -> CachingWeatherService -> FailoverWeatherProvider
 * -> [CircuitBreakingWeatherProvider -> WeatherstackProvider, CircuitBreakingWeatherProvider -> OpenWeatherMapProvider].
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WeatherProperties.class)
class WeatherConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Breakers read time through the application clock, so their recovery can be tested without waiting. */
    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(WeatherProperties properties, Clock clock) {
        WeatherProperties.CircuitBreakerSettings settings = properties.circuitBreaker();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.slidingWindowSize())
                .minimumNumberOfCalls(settings.minimumNumberOfCalls())
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(settings.permittedCallsInHalfOpenState())
                .clock(clock)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    @Bean
    WeatherstackProvider weatherstackProvider(RestClient.Builder restClientBuilder, WeatherProperties properties) {
        ProviderSettings settings = requireConfigured(properties.providers().weatherstack(),
                WeatherstackProvider.NAME, "WEATHERSTACK_ACCESS_KEY");
        return new WeatherstackProvider(restClient(restClientBuilder, settings), settings.apiKey());
    }

    @Bean
    OpenWeatherMapProvider openWeatherMapProvider(RestClient.Builder restClientBuilder, WeatherProperties properties) {
        ProviderSettings settings = requireConfigured(properties.providers().openweathermap(),
                OpenWeatherMapProvider.NAME, "OPENWEATHERMAP_API_KEY");
        return new OpenWeatherMapProvider(restClient(restClientBuilder, settings), settings.apiKey());
    }

    @Bean
    WeatherCache weatherCache() {
        return new InMemoryWeatherCache();
    }

    @Bean
    WeatherService weatherService(
            WeatherstackProvider weatherstack,
            OpenWeatherMapProvider openWeatherMap,
            CircuitBreakerRegistry circuitBreakers,
            WeatherCache cache,
            WeatherProperties properties,
            Clock clock) {
        // Failover order: Weatherstack is the primary, OpenWeatherMap the fallback.
        List<WeatherProvider> priorityOrder = List.of(weatherstack, openWeatherMap);
        List<WeatherProvider> guarded = priorityOrder.stream()
                .map(provider -> (WeatherProvider) new CircuitBreakingWeatherProvider(
                        provider, circuitBreakers.circuitBreaker(provider.name())))
                .toList();
        return new CachingWeatherService(new FailoverWeatherProvider(guarded), cache, properties.cacheTtl(), clock);
    }

    private static ProviderSettings requireConfigured(ProviderSettings settings, String provider, String keyEnvVar) {
        Assert.notNull(settings, () -> "weather.providers." + provider + " must be configured");
        Assert.hasText(settings.baseUrl(), () -> "weather.providers." + provider + ".base-url must be set");
        Assert.hasText(settings.apiKey(), () -> "weather.providers." + provider
                + ".api-key must be set (environment variable " + keyEnvVar + ")");
        Assert.notNull(settings.timeout(), () -> "weather.providers." + provider + ".timeout must be set");
        return settings;
    }

    /**
     * One client per provider so that each gets its own base URL and timeouts. HTTP/1.1 is pinned
     * because neither provider benefits from HTTP/2 and the JDK client would otherwise attempt an
     * h2c upgrade on every plain-HTTP request.
     */
    private static RestClient restClient(RestClient.Builder builder, ProviderSettings settings) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(settings.timeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(settings.timeout());
        return builder.baseUrl(settings.baseUrl()).requestFactory(requestFactory).build();
    }
}
