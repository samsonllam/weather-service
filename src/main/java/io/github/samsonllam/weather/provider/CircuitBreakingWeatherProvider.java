package io.github.samsonllam.weather.provider;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.ProviderSkippedException;
import io.github.samsonllam.weather.domain.Weather;
import io.github.samsonllam.weather.domain.WeatherProvider;

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
            throw new ProviderSkippedException(name(), "circuit breaker is " + circuitBreaker.getState(), e);
        }
    }
}
