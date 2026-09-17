package io.github.samsonllam.weather.domain;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * Wraps a provider in a circuit breaker so that a provider which keeps failing is skipped
 * immediately, instead of costing every request a full timeout before failover kicks in.
 */
public final class CircuitBreakingWeatherProvider implements WeatherProvider {

    private final WeatherProvider delegate;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakingWeatherProvider(WeatherProvider delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public Weather currentWeather(City city) {
        try {
            return circuitBreaker.executeSupplier(() -> delegate.currentWeather(city));
        } catch (CallNotPermittedException e) {
            throw new ProviderException(name(), "skipped, circuit breaker is " + circuitBreaker.getState(), e);
        }
    }
}
