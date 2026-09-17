package io.github.samsonllam.weather.provider.openweathermap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.ProviderException;
import io.github.samsonllam.weather.domain.Weather;
import io.github.samsonllam.weather.support.FakeProviderServer;
import io.github.samsonllam.weather.support.ProviderPayloads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenWeatherMapProviderTest {

    private final FakeProviderServer server = new FakeProviderServer();
    private final OpenWeatherMapProvider provider =
            new OpenWeatherMapProvider(RestClient.builder().baseUrl(server.baseUrl()).build(), "secret-key");

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void readsMetricTemperatureAndConvertsWindFromMetresPerSecondToKph() {
        server.respond(200, ProviderPayloads.openWeatherMap(30.4, 5.0));

        Weather weather = provider.currentWeather(City.SINGAPORE);

        assertThat(weather.temperatureCelsius()).isEqualTo(30.4);
        assertThat(weather.windSpeedKph()).isCloseTo(18.0, within(1e-9));
        assertThat(server.lastRequestUri().getPath()).isEqualTo("/data/2.5/weather");
        assertThat(server.lastRequestUri().getQuery()).isEqualTo("q=Singapore,SG&appid=secret-key&units=metric");
    }

    @Test
    void treatsAnAuthenticationErrorAsAFailureWithoutLeakingTheUrl() {
        server.respond(401, ProviderPayloads.OPENWEATHERMAP_INVALID_KEY);

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessage("openweathermap: HTTP 401")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-key"));
    }

    @Test
    void treatsAPayloadWithoutWindAsAFailure() {
        server.respond(200, "{\"main\": {\"temp\": 30.4}}");

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("missing temperature or wind speed");
    }
}
