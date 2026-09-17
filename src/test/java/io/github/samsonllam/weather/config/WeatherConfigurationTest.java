package io.github.samsonllam.weather.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.samsonllam.weather.domain.WeatherService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

/** Start-up validation: a misconfigured service must fail with a message that names the missing setting. */
class WeatherConfigurationTest {

    private static final Map<String, String> COMPLETE = Map.ofEntries(
            Map.entry("weather.cache-ttl", "3s"),
            Map.entry("weather.providers.weatherstack.base-url", "http://127.0.0.1:1"),
            Map.entry("weather.providers.weatherstack.api-key", "ws-key"),
            Map.entry("weather.providers.weatherstack.timeout", "1s"),
            Map.entry("weather.providers.openweathermap.base-url", "http://127.0.0.1:1"),
            Map.entry("weather.providers.openweathermap.api-key", "owm-key"),
            Map.entry("weather.providers.openweathermap.timeout", "1s"),
            Map.entry("weather.circuit-breaker.sliding-window-size", "6"),
            Map.entry("weather.circuit-breaker.minimum-number-of-calls", "3"),
            Map.entry("weather.circuit-breaker.failure-rate-threshold", "50"),
            Map.entry("weather.circuit-breaker.wait-duration-in-open-state", "30s"),
            Map.entry("weather.circuit-breaker.permitted-calls-in-half-open-state", "1"));

    @Test
    void wiresTheServiceWhenFullyConfigured() {
        runnerWith(COMPLETE).run(context -> assertThat(context).hasSingleBean(WeatherService.class));
    }

    @Test
    void refusesToStartWithAnEmptyWeatherstackKey() {
        runnerWith(with("weather.providers.weatherstack.api-key", "")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("weather.providers.weatherstack.api-key must be set")
                    .hasMessageContaining("WEATHERSTACK_ACCESS_KEY");
        });
    }

    @Test
    void refusesToStartWithAnEmptyOpenWeatherMapKey() {
        runnerWith(with("weather.providers.openweathermap.api-key", "")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("OPENWEATHERMAP_API_KEY");
        });
    }

    @Test
    void refusesToStartWithoutACacheTtl() {
        runnerWith(without("weather.cache-ttl")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("weather.cache-ttl must be set");
        });
    }

    private static ApplicationContextRunner runnerWith(Map<String, String> properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(WeatherConfiguration.class)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withPropertyValues(properties.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .toArray(String[]::new));
    }

    private static Map<String, String> with(String key, String value) {
        Map<String, String> properties = new LinkedHashMap<>(COMPLETE);
        properties.put(key, value);
        return properties;
    }

    private static Map<String, String> without(String key) {
        Map<String, String> properties = new LinkedHashMap<>(COMPLETE);
        properties.remove(key);
        return properties;
    }
}
