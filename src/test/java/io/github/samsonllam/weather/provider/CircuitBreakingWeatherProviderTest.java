package io.github.samsonllam.weather.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.ProviderException;
import io.github.samsonllam.weather.domain.ProviderSkippedException;
import io.github.samsonllam.weather.domain.Weather;
import io.github.samsonllam.weather.support.MutableClock;
import io.github.samsonllam.weather.support.StubWeatherProvider;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CircuitBreakingWeatherProviderTest {

    private static final Weather WEATHER = new Weather(29, 20);
    private static final Duration OPEN_FOR = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-17T04:00:00Z"));
    private final StubWeatherProvider delegate = new StubWeatherProvider("stub");
    private final CircuitBreaker circuitBreaker = CircuitBreaker.of("stub", CircuitBreakerConfig.custom()
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(OPEN_FOR)
            .permittedNumberOfCallsInHalfOpenState(1)
            .clock(clock)
            .build());
    private final CircuitBreakingWeatherProvider guarded = new CircuitBreakingWeatherProvider(delegate, circuitBreaker);

    @Test
    void passesResultsAndNameThrough() {
        delegate.willReturn(WEATHER);

        assertThat(guarded.currentWeather(City.SINGAPORE)).isEqualTo(WEATHER);
        assertThat(guarded.name()).isEqualTo("stub");
    }

    @Test
    void propagatesProviderFailuresWhileTheCircuitIsClosed() {
        delegate.willFail("HTTP 503");

        assertThatThrownBy(() -> guarded.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .isNotInstanceOf(ProviderSkippedException.class)
                .hasMessage("stub: HTTP 503");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void skipsTheProviderOnceTheCircuitHasOpened() {
        openTheCircuit();
        int callsBeforeOpen = delegate.callCount();

        assertThatThrownBy(() -> guarded.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderSkippedException.class)
                .hasMessage("stub: circuit breaker is OPEN");
        assertThat(delegate.callCount()).as("the delegate is not called while the circuit is open").isEqualTo(callsBeforeOpen);
    }

    @Test
    void retriesTheProviderOnceTheOpenPeriodHasPassedAndClosesOnSuccess() {
        openTheCircuit();
        delegate.willReturn(WEATHER);
        clock.advance(OPEN_FOR.plusMillis(1));

        assertThat(guarded.currentWeather(City.SINGAPORE)).isEqualTo(WEATHER);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void reopensWhenTheTrialCallAfterTheOpenPeriodFails() {
        openTheCircuit();
        clock.advance(OPEN_FOR.plusMillis(1));

        assertThatThrownBy(() -> guarded.currentWeather(City.SINGAPORE)).hasMessage("stub: HTTP 503");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private void openTheCircuit() {
        delegate.willFail("HTTP 503");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> guarded.currentWeather(City.SINGAPORE)).isInstanceOf(ProviderException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
