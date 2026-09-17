package io.github.samsonllam.weather.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.samsonllam.weather.support.StubWeatherProvider;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CircuitBreakingWeatherProviderTest {

    private static final Weather WEATHER = new Weather(29, 20);

    private final StubWeatherProvider delegate = new StubWeatherProvider("stub");
    private final CircuitBreaker circuitBreaker = CircuitBreaker.of("stub", CircuitBreakerConfig.custom()
            .slidingWindowSize(2)
            .minimumNumberOfCalls(2)
            .failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofMinutes(1))
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
                .hasMessage("stub: HTTP 503");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void skipsTheProviderOnceTheCircuitHasOpened() {
        delegate.willFail("HTTP 503");
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> guarded.currentWeather(City.SINGAPORE)).isInstanceOf(ProviderException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int callsBeforeOpen = delegate.callCount();

        assertThatThrownBy(() -> guarded.currentWeather(City.SINGAPORE))
                .isInstanceOf(ProviderException.class)
                .hasMessageContaining("circuit breaker is OPEN");
        assertThat(delegate.callCount()).as("the delegate is not called while the circuit is open").isEqualTo(callsBeforeOpen);
    }
}
