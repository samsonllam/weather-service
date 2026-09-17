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
        assertThat(report.stale()).isFalse();
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
    void withAZeroTtlEveryRequestAfterTheFirstInstantAsksTheProvider() {
        CachingWeatherService uncached = new CachingWeatherService(provider, new InMemoryWeatherCache(), Duration.ZERO, clock);
        provider.willReturn(FIRST);
        uncached.currentWeather(City.SINGAPORE);
        clock.advance(Duration.ofMillis(1));

        uncached.currentWeather(City.SINGAPORE);

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
    void servesStaleWhenTheProviderThrowsSomethingUnexpected() {
        provider.willReturn(FIRST);
        service.currentWeather(City.SINGAPORE);
        clock.advance(Duration.ofMinutes(10));
        provider.willThrow(new IllegalStateException("bug"));

        WeatherReport report = service.currentWeather(City.SINGAPORE);

        assertThat(report.weather()).isEqualTo(FIRST);
        assertThat(report.stale()).isTrue();
    }

    @Test
    void asksTheProviderAtMostOncePerTtlDuringAnOutage() {
        provider.willReturn(FIRST);
        service.currentWeather(City.SINGAPORE);
        clock.advance(TTL.plusSeconds(1));
        provider.willFail("all providers down");

        assertThat(service.currentWeather(City.SINGAPORE).stale()).isTrue();
        clock.advance(TTL);
        assertThat(service.currentWeather(City.SINGAPORE).stale()).isTrue();
        assertThat(provider.callCount()).as("second request within the TTL of the failed attempt").isEqualTo(2);

        clock.advance(Duration.ofMillis(1));
        assertThat(service.currentWeather(City.SINGAPORE).stale()).isTrue();
        assertThat(provider.callCount()).as("the providers are probed again once the TTL has passed").isEqualTo(3);
    }

    @Test
    void recoversFromStaleAsSoonAsTheProviderIsBack() {
        provider.willReturn(FIRST);
        service.currentWeather(City.SINGAPORE);
        clock.advance(Duration.ofMinutes(10));
        provider.willFail("all providers down");
        service.currentWeather(City.SINGAPORE);
        provider.willReturn(SECOND);
        clock.advance(TTL.plusMillis(1));

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

        List<WeatherReport> reports = requestConcurrently(16);

        assertThat(reports).allSatisfy(report -> {
            assertThat(report.weather()).isEqualTo(SECOND);
            assertThat(report.stale()).isFalse();
        });
        assertThat(provider.callCount()).as("one priming call plus one shared refresh").isEqualTo(2);
    }

    @Test
    void concurrentRequestsDuringAnOutageShareASingleFailedAttempt() throws Exception {
        provider.willReturn(FIRST);
        service.currentWeather(City.SINGAPORE);
        clock.advance(TTL.plusSeconds(1));
        provider.willFail("all providers down");
        provider.holdCalls();

        List<WeatherReport> reports = requestConcurrently(16);

        assertThat(reports).allSatisfy(report -> {
            assertThat(report.weather()).isEqualTo(FIRST);
            assertThat(report.stale()).isTrue();
        });
        assertThat(provider.callCount()).as("one priming call plus one shared failed attempt").isEqualTo(2);
    }

    @Test
    void rejectsANegativeTtl() {
        assertThatThrownBy(() -> new CachingWeatherService(provider, new InMemoryWeatherCache(), Duration.ofSeconds(-1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Fires {@code count} requests at once, lets exactly one reach the held provider, then releases it. */
    private List<WeatherReport> requestConcurrently(int count) throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<WeatherReport>> requests = IntStream.range(0, count)
                    .mapToObj(i -> pool.submit(() -> service.currentWeather(City.SINGAPORE)))
                    .toList();
            await().atMost(5, TimeUnit.SECONDS).until(() -> provider.inFlight() == 1);
            provider.releaseCalls();
            List<WeatherReport> reports = new java.util.ArrayList<>();
            for (Future<WeatherReport> request : requests) {
                reports.add(request.get(5, TimeUnit.SECONDS));
            }
            return reports;
        }
    }
}
