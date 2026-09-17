package io.github.samsonllam.weather.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.samsonllam.weather.support.StubWeatherProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class FailoverWeatherProviderTest {

    private static final Weather PRIMARY_WEATHER = new Weather(29, 20);
    private static final Weather SECONDARY_WEATHER = new Weather(30, 18);

    private final StubWeatherProvider primary = new StubWeatherProvider("primary");
    private final StubWeatherProvider secondary = new StubWeatherProvider("secondary");
    private final FailoverWeatherProvider failover = new FailoverWeatherProvider(List.of(primary, secondary));

    @Test
    void usesThePrimaryProviderWhenItSucceeds() {
        primary.willReturn(PRIMARY_WEATHER);
        secondary.willReturn(SECONDARY_WEATHER);

        assertThat(failover.currentWeather(City.SINGAPORE)).isEqualTo(PRIMARY_WEATHER);
        assertThat(secondary.callCount()).isZero();
    }

    @Test
    void fallsBackToTheNextProviderWhenThePrimaryFails() {
        primary.willFail("HTTP 503");
        secondary.willReturn(SECONDARY_WEATHER);

        assertThat(failover.currentWeather(City.SINGAPORE)).isEqualTo(SECONDARY_WEATHER);
    }

    @Test
    void fallsBackToTheNextProviderWhenThePrimaryIsSkippedByItsCircuitBreaker() {
        primary.willThrow(new ProviderSkippedException("primary", "circuit breaker is OPEN", null));
        secondary.willReturn(SECONDARY_WEATHER);

        assertThat(failover.currentWeather(City.SINGAPORE)).isEqualTo(SECONDARY_WEATHER);
    }

    @Test
    void treatsAnUnexpectedExceptionFromAProviderAsAFailure() {
        primary.willThrow(new NullPointerException("bug in the adapter"));
        secondary.willReturn(SECONDARY_WEATHER);

        assertThat(failover.currentWeather(City.SINGAPORE)).isEqualTo(SECONDARY_WEATHER);
    }

    @Test
    void reportsEveryFailureWhenAllProvidersFail() {
        primary.willFail("HTTP 503");
        secondary.willFail("I/O failure: ConnectException");

        assertThatThrownBy(() -> failover.currentWeather(City.SINGAPORE))
                .isInstanceOf(AllProvidersFailedException.class)
                .hasMessage("failover: all 2 providers failed")
                .satisfies(e -> assertThat(e.getSuppressed())
                        .extracting(Throwable::getMessage)
                        .containsExactly("primary: HTTP 503", "secondary: I/O failure: ConnectException"));
    }

    @Test
    void requiresAtLeastOneProvider() {
        assertThatThrownBy(() -> new FailoverWeatherProvider(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
