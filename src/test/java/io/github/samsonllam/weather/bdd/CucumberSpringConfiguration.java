package io.github.samsonllam.weather.bdd;

import io.cucumber.spring.CucumberContextConfiguration;
import io.github.samsonllam.weather.support.FakeProviderServer;
import io.github.samsonllam.weather.support.TestClockConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots the whole application on a random port, with both providers pointed at local fake servers
 * and the clock under test control. One context is shared by every scenario.
 */
@CucumberContextConfiguration
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "weather.providers.weatherstack.api-key=test-weatherstack-key",
        "weather.providers.openweathermap.api-key=test-openweathermap-key",
        "weather.providers.weatherstack.timeout=1s",
        "weather.providers.openweathermap.timeout=1s"
})
@Import(TestClockConfiguration.class)
public class CucumberSpringConfiguration {

    static final FakeProviderServer WEATHERSTACK = new FakeProviderServer();
    static final FakeProviderServer OPENWEATHERMAP = new FakeProviderServer();

    @DynamicPropertySource
    static void providerUrls(DynamicPropertyRegistry registry) {
        registry.add("weather.providers.weatherstack.base-url", WEATHERSTACK::baseUrl);
        registry.add("weather.providers.openweathermap.base-url", OPENWEATHERMAP::baseUrl);
    }
}
