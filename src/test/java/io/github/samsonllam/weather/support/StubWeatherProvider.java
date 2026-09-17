package io.github.samsonllam.weather.support;

import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.ProviderException;
import io.github.samsonllam.weather.domain.Weather;
import io.github.samsonllam.weather.domain.WeatherProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** A scriptable provider: returns a fixed observation, throws a fixed exception, or blocks until released. */
public final class StubWeatherProvider implements WeatherProvider {

    private final String name;
    private volatile Weather weather;
    private volatile RuntimeException error;
    private volatile CountDownLatch gate;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();

    public StubWeatherProvider(String name) {
        this.name = name;
    }

    public StubWeatherProvider willReturn(Weather weather) {
        this.weather = weather;
        this.error = null;
        return this;
    }

    public StubWeatherProvider willFail(String reason) {
        return willThrow(new ProviderException(name, reason));
    }

    public StubWeatherProvider willThrow(RuntimeException error) {
        this.error = error;
        return this;
    }

    /** Makes every subsequent call block until {@link #releaseCalls()}. */
    public void holdCalls() {
        gate = new CountDownLatch(1);
    }

    public void releaseCalls() {
        CountDownLatch currentGate = gate;
        if (currentGate != null) {
            currentGate.countDown();
        }
    }

    public int callCount() {
        return calls.get();
    }

    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Weather currentWeather(City city) {
        calls.incrementAndGet();
        inFlight.incrementAndGet();
        try {
            CountDownLatch currentGate = gate;
            if (currentGate != null) {
                currentGate.await();
            }
            if (error != null) {
                throw error;
            }
            return weather;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for release", e);
        } finally {
            inFlight.decrementAndGet();
        }
    }
}
