package io.github.samsonllam.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.samsonllam.weather.domain.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "weather.providers.weatherstack.api-key=test-weatherstack-key",
        "weather.providers.openweathermap.api-key=test-openweathermap-key"
})
class WeatherServiceApplicationTest {

    @Autowired
    private WeatherService weatherService;

    @Test
    void wiresTheWeatherPipeline() {
        assertThat(weatherService).isNotNull();
    }

    @Test
    void refusesToStartWithoutAProviderKey() {
        SpringApplication application = new SpringApplication(WeatherServiceApplication.class);

        assertThatThrownBy(() -> application.run(
                "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off",
                "--weather.providers.weatherstack.api-key=",
                "--weather.providers.openweathermap.api-key=test-openweathermap-key"))
                .rootCause()
                .hasMessageContaining("weather.providers.weatherstack.api-key must be set")
                .hasMessageContaining("WEATHERSTACK_ACCESS_KEY");
    }
}
