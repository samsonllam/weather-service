package io.github.samsonllam.weather.provider.openweathermap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.ProviderException;
import io.github.samsonllam.weather.domain.Weather;
import io.github.samsonllam.weather.support.FakeProviderServer;
import io.github.samsonllam.weather.support.ProviderPayloads;
import io.github.samsonllam.weather.support.StackTraces;
import io.github.samsonllam.weather.support.TestRestClients;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenWeatherMapProviderTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final String API_KEY = "secret-key";

    private final FakeProviderServer server = new FakeProviderServer();
    private final OpenWeatherMapProvider provider =
            new OpenWeatherMapProvider(TestRestClients.withTimeout(server.baseUrl(), TIMEOUT), API_KEY);

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
    void treatsAnAuthenticationErrorAsAFailureWithoutQuotingABodyThatEchoesTheKey() {
        server.respond(401, "{\"cod\": 401, \"message\": \"Invalid API key secret-key\"}");

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessage("openweathermap: HTTP 401")
                .satisfies(e -> assertThat(StackTraces.of(e)).doesNotContain(API_KEY));
    }

    @Test
    void treatsAPayloadWithoutWindAsAFailure() {
        server.respond(200, "{\"main\": {\"temp\": 30.4}}");

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("missing temperature or wind speed");
    }

    @Test
    void treatsAnImplausibleReadingAsAFailure() {
        server.respond(200, ProviderPayloads.openWeatherMap(30.4, -1.0));

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessage("openweathermap: implausible wind speed: -3.6 km/h");
    }
}
