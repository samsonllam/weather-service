package io.github.samsonllam.weather.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.samsonllam.weather.support.MutableClock;
import io.github.samsonllam.weather.support.StubWeatherProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CachingWeatherServiceTest {

    private static final Duration TTL = Duration.ofSeconds(3);
    private static final Weather FIRST = new Weather(29, 20);
    private static final Weather SECOND = new Weather(31, 12);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-17T04:00:00Z"));
    private final StubWeatherProvider provider = new StubWeatherProvider("stub");
    private final CachingWeatherService service =
            new CachingWeatherService(provider, new InMemoryWeatherCache(), TTL, clock);

    @Test
    void fetchesFromTheProviderWhenNothingIsCached() {
        provider.willReturn(FIRST);

        WeatherReport report = service.currentWeather(City.SINGAPORE);

        assertThat(report.weather()).isEqualTo(FIRST);
        assertThat(report.stale()).isFalse();
        assertThat(report.fetchedAt()).isEqualTo(clock.instant());
    }

    @Test
    void servesTheCachedResultWithinTheTtlWithoutCallingTheProvider() {
        provider.willReturn(FIRST);
        service.currentWeather(City.SINGAPORE);
        provider.willReturn(SECOND);
        clock.advance(TTL);

        WeatherReport report = service.currentWeather(City.SINGAPORE);

        assertThat(report.weather()).isEqualTo(FIRST);
        assertThat(provider.callCount()).isEqualTo(1);
    }

    @Test
    void refreshesFromTheProviderOnceTheTtlHasPassed() {
        provider.willReturn(FIRST);
        service.currentWeather(City.SINGAPORE);
        provider.willReturn(SECOND);
        clock.advance(TTL.plusMillis(1));

        WeatherReport report = service.currentWeather(City.SINGAPORE);

        assertThat(report.weather()).isEqualTo(SECOND);
        assertThat(report.stale()).isFalse();
        assertThat(provider.callCount()).isEqualTo(2);
    }

    @Test
    void servesTheExpiredResultAsStaleWhenTheProviderFails() {
        provider.willReturn(FIRST);
        service.currentWeather(City.SINGAPORE);
        Instant fetchedAt = clock.instant();
        clock.advance(Duration.ofMinutes(10));
        provider.willFail("all providers down");

        WeatherReport report = service.currentWeather(City.SINGAPORE);

        assertThat(report.weather()).isEqualTo(FIRST);
        assertThat(report.stale()).isTrue();
        assertThat(report.fetchedAt()).isEqualTo(fetchedAt);
    }

    @Test
    void recoversFromStaleAsSoonAsTheProviderIsBack() {
        provider.willReturn(FIRST);
        service.currentWeather(City.SINGAPORE);
        clock.advance(Duration.ofMinutes(10));
        provider.willFail("all providers down");
        service.currentWeather(City.SINGAPORE);
        provider.willReturn(SECOND);

        WeatherReport report = service.currentWeather(City.SINGAPORE);

        assertThat(report.weather()).isEqualTo(SECOND);
        assertThat(report.stale()).isFalse();
    }

    @Test
    void failsWhenTheProviderFailsAndNothingWasEverCached() {
        provider.willFail("all providers down");

        assertThatThrownBy(() -> service.currentWeather(City.SINGAPORE))
                .isInstanceOf(WeatherUnavailableException.class)
                .hasCauseInstanceOf(ProviderException.class);
    }

    @Test
    void concurrentRequestsAfterExpiryShareASingleProviderCall() throws Exception {
        provider.willReturn(FIRST);
        service.currentWeather(City.SINGAPORE);
        clock.advance(TTL.plusSeconds(1));
        provider.willReturn(SECOND);
        provider.holdCalls();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<WeatherReport>> requests = IntStream.range(0, 16)
                    .mapToObj(i -> pool.submit(() -> service.currentWeather(City.SINGAPORE)))
                    .toList();
            await().atMost(5, TimeUnit.SECONDS).until(() -> provider.inFlight() == 1);
            provider.releaseCalls();

            for (Future<WeatherReport> request : requests) {
                WeatherReport report = request.get(5, TimeUnit.SECONDS);
                assertThat(report.weather()).isEqualTo(SECOND);
                assertThat(report.stale()).isFalse();
            }
        }
        assertThat(provider.callCount()).as("one priming call plus one shared refresh").isEqualTo(2);
    }

    @Test
    void rejectsANegativeTtl() {
        assertThatThrownBy(() -> new CachingWeatherService(provider, new InMemoryWeatherCache(), Duration.ofSeconds(-1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
