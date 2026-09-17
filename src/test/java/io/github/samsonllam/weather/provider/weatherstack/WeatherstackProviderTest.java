package io.github.samsonllam.weather.provider.weatherstack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.ProviderException;
import io.github.samsonllam.weather.domain.Weather;
import io.github.samsonllam.weather.support.FakeProviderServer;
import io.github.samsonllam.weather.support.ProviderPayloads;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class WeatherstackProviderTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final FakeProviderServer server = new FakeProviderServer();
    private final WeatherstackProvider provider = new WeatherstackProvider(restClient(server.baseUrl()), "secret-key");

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void readsTemperatureAndWindSpeedAndSendsTheAccessKey() {
        server.respond(200, ProviderPayloads.weatherstack(29, 20));

        Weather weather = provider.currentWeather(City.SINGAPORE);

        assertThat(weather).isEqualTo(new Weather(29, 20));
        assertThat(server.lastRequestUri().getPath()).isEqualTo("/current");
        assertThat(server.lastRequestUri().getQuery()).isEqualTo("access_key=secret-key&query=Singapore");
    }

    @Test
    void treatsAnErrorPayloadWithHttp200AsAFailure() {
        server.respond(200, ProviderPayloads.WEATHERSTACK_INVALID_KEY);

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessage("weatherstack: API error 101 (invalid_access_key): You have not supplied a valid API Access Key.");
    }

    @Test
    void treatsAPayloadWithoutCurrentWeatherAsAFailure() {
        server.respond(200, "{\"location\": {\"name\": \"Singapore\"}}");

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("missing current temperature or wind speed");
    }

    @Test
    void treatsAServerErrorAsAFailureWithoutLeakingTheUrl() {
        server.respond(503, "{}");

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessage("weatherstack: HTTP 503")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret-key"));
    }

    @Test
    void givesUpOnAProviderThatDoesNotAnswerWithinTheTimeout() {
        server.respond(200, ProviderPayloads.weatherstack(29, 20)).respondAfter(TIMEOUT.multipliedBy(4));

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageStartingWith("weatherstack: I/O failure: HttpTimeoutException");
    }

    @Test
    void treatsAnUnreachableProviderAsAFailure() {
        server.close();

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageStartingWith("weatherstack: I/O failure:");
    }

    private static RestClient restClient(String baseUrl) {
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        requestFactory.setReadTimeout(TIMEOUT);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
