package io.github.samsonllam.weather.provider.weatherstack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class WeatherstackProviderTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final String ACCESS_KEY = "secret-key";

    private final FakeProviderServer server = new FakeProviderServer();
    private final WeatherstackProvider provider =
            new WeatherstackProvider(TestRestClients.withTimeout(server.baseUrl(), TIMEOUT), ACCESS_KEY);

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void readsTemperatureAndWindSpeedAndAsksForMetricUnits() {
        server.respond(200, ProviderPayloads.weatherstack(29, 20));

        Weather weather = provider.currentWeather(City.SINGAPORE);

        assertThat(weather).isEqualTo(new Weather(29, 20));
        assertThat(server.lastRequestUri().getPath()).isEqualTo("/current");
        assertThat(server.lastRequestUri().getQuery()).isEqualTo("access_key=secret-key&query=Singapore&units=m");
    }

    @Test
    void treatsAnErrorPayloadWithHttp200AsAFailureDescribedInItsOwnWords() {
        server.respond(200, ProviderPayloads.WEATHERSTACK_INVALID_KEY);

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessage("weatherstack: API error 101 (invalid access key)");
    }

    @Test
    void neverRepeatsUpstreamErrorTextWhichCouldEchoTheKey() {
        server.respond(200, """
                {"success": false, "error": {"code": 104, "type": "usage_limit\\nkey=secret-key",
                 "info": "Your key secret-key has reached its limit"}}
                """);

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessage("weatherstack: API error 104 (monthly usage limit reached)")
                .satisfies(e -> assertThat(StackTraces.of(e)).doesNotContain(ACCESS_KEY));
    }

    @Test
    void treatsAPayloadWithoutCurrentWeatherAsAFailure() {
        server.respond(200, "{\"location\": {\"name\": \"Singapore\"}}");

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("missing current temperature or wind speed");
    }

    @Test
    void treatsAnEmptyBodyAsAFailure() {
        server.respond(200, "");

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessage("weatherstack: empty response body");
    }

    @Test
    void treatsAnImplausibleReadingAsAFailure() {
        server.respond(200, ProviderPayloads.weatherstack(999, 20));

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessage("weatherstack: implausible temperature: 999.0 C");
    }

    @Test
    void treatsABodyThatIsNotJsonAsAFailureWithoutQuotingIt() {
        server.respond(200, "<html><body>Bad key secret-key</body></html>");

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageStartingWith("weatherstack: unreadable response: ")
                .satisfies(e -> assertThat(StackTraces.of(e)).doesNotContain(ACCESS_KEY));
    }

    @Test
    void treatsAMalformedContentTypeAsAFailureWithoutQuotingIt() {
        server.respond(200, ProviderPayloads.weatherstack(29, 20)).withContentType("key=secret-key");

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageStartingWith("weatherstack: malformed response: ")
                .satisfies(e -> assertThat(StackTraces.of(e)).doesNotContain(ACCESS_KEY));
    }

    @Test
    void treatsAServerErrorAsAFailureWithoutQuotingABodyThatEchoesTheKey() {
        server.respond(503, "{\"echo\": \"access_key=secret-key\"}");

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessage("weatherstack: HTTP 503")
                .satisfies(e -> assertThat(StackTraces.of(e)).doesNotContain(ACCESS_KEY));
    }

    @Test
    void givesUpOnAProviderThatDoesNotAnswerWithinTheTimeout() {
        server.respond(200, ProviderPayloads.weatherstack(29, 20)).respondAfter(TIMEOUT.multipliedBy(4));

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageStartingWith("weatherstack: I/O failure: ")
                .hasMessageContaining("TimeoutException")
                .hasNoCause()
                .satisfies(e -> assertThat(StackTraces.of(e)).doesNotContain(ACCESS_KEY));
    }

    @Test
    void givesUpOnAProviderThatSendsHeadersButStallsTheBody() {
        server.respond(200, ProviderPayloads.weatherstack(29, 20)).stallBodyFor(TIMEOUT.multipliedBy(4));

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("TimeoutException");
    }

    @Test
    void treatsAnUnreachableProviderAsAFailure() {
        server.close();

        assertThatThrownBy(() -> provider.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageStartingWith("weatherstack: I/O failure:");
    }
}
