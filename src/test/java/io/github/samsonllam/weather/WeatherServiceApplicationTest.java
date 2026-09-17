package io.github.samsonllam.weather;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.samsonllam.weather.domain.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
}
