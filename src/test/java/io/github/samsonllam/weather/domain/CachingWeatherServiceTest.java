package io.github.samsonllam.weather.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.samsonllam.weather.support.MutableClock;
import io.github.samsonllam.weather.support.StubWeatherProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CachingWeatherServiceTest {

    private static final Duration TTL = Duration.ofSeconds(3);
    private static final Weather FIRST = new Weather(29, 20);
    private static final Weather SECOND = new Weather(31, 12);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-17T04:00:00Z"));
    private final StubWeatherProvider provider = new StubWeatherProvider("stub");
    private final CachingWeatherService service =
            new CachingWeatherService(provider, new InMemoryWeatherCache(), TTL, clock);
    /**
     * For the contention tests: expiry is driven by the fake clock, while the real-time wait for the
     * lock is bounded by the TTL, so a long TTL keeps a slow CI scheduler from timing the waiters out.
     */
    private static final Duration LONG_TTL = Duration.ofMinutes(1);
    private final CachingWeatherService patient =
            new CachingWeatherService(provider, new InMemoryWeatherCache(), LONG_TTL, clock);
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void releaseEverything() {
        provider.releaseCalls();
        pool.shutdownNow();
    }

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
    void doesNotRepeatAFailedAttemptWithinTheTtlEvenWithNothingCached() {
        provider.willFail("all providers down");
        assertThatThrownBy(() -> service.currentWeather(City.SINGAPORE)).isInstanceOf(WeatherUnavailableException.class);

        assertThatThrownBy(() -> service.currentWeather(City.SINGAPORE))
                .isInstanceOf(WeatherUnavailableException.class)
                .hasCauseInstanceOf(ProviderException.class);
        assertThat(provider.callCount()).as("the failure is answered from memory within the TTL").isEqualTo(1);

        clock.advance(TTL.plusMillis(1));
        provider.willReturn(FIRST);
        assertThat(service.currentWeather(City.SINGAPORE).weather()).isEqualTo(FIRST);
        assertThat(provider.callCount()).isEqualTo(2);
    }

    @Test
    void concurrentRequestsAfterExpiryShareASingleProviderCall() throws Exception {
        provider.willReturn(FIRST);
        patient.currentWeather(City.SINGAPORE);
        clock.advance(LONG_TTL.plusSeconds(1));
        provider.willReturn(SECOND);

        Contention contention = contend(patient, 16);
        provider.releaseCalls();

        for (Future<WeatherReport> request : contention.requests()) {
            WeatherReport report = request.get(5, TimeUnit.SECONDS);
            assertThat(report.weather()).isEqualTo(SECOND);
            assertThat(report.stale()).isFalse();
        }
        assertThat(provider.callCount()).as("one priming call plus one shared refresh").isEqualTo(2);
    }

    @Test
    void aRefreshSlowerThanTheTtlIsStillSharedWithTheRequestsThatWaitedForIt() throws Exception {
        provider.willReturn(FIRST);
        patient.currentWeather(City.SINGAPORE);
        clock.advance(LONG_TTL.plusSeconds(1));
        provider.willReturn(SECOND);

        Contention contention = contend(patient, 16);
        clock.advance(LONG_TTL.plusSeconds(1));
        provider.releaseCalls();

        for (Future<WeatherReport> request : contention.requests()) {
            WeatherReport report = request.get(5, TimeUnit.SECONDS);
            assertThat(report.weather()).isEqualTo(SECOND);
            assertThat(report.stale()).isFalse();
        }
        assertThat(provider.callCount()).as("the slow refresh is not repeated by the waiters").isEqualTo(2);
    }

    @Test
    void aFailedRefreshSlowerThanTheTtlIsStillSharedWithTheRequestsThatWaitedForIt() throws Exception {
        provider.willReturn(FIRST);
        patient.currentWeather(City.SINGAPORE);
        clock.advance(LONG_TTL.plusSeconds(1));
        provider.willFail("all providers down");

        Contention contention = contend(patient, 16);
        clock.advance(LONG_TTL.plusSeconds(1));
        provider.releaseCalls();

        for (Future<WeatherReport> request : contention.requests()) {
            WeatherReport report = request.get(5, TimeUnit.SECONDS);
            assertThat(report.weather()).isEqualTo(FIRST);
            assertThat(report.stale()).isTrue();
        }
        assertThat(provider.callCount()).as("one priming call plus one shared failed attempt").isEqualTo(2);
    }

    @Test
    void concurrentRequestsWithNothingCachedShareASingleFailedAttempt() throws Exception {
        provider.willFail("all providers down");

        Contention contention = contend(patient, 16);
        provider.releaseCalls();

        for (Future<WeatherReport> request : contention.requests()) {
            assertThatThrownBy(() -> request.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(WeatherUnavailableException.class);
        }
        assertThat(provider.callCount()).as("the cold-start failure is shared too").isEqualTo(1);
    }

    @Test
    void aRequestThatWaitedOneTtlForARefreshTakesTheLastKnownValueInstead() throws Exception {
        Duration shortTtl = Duration.ofMillis(200);
        CachingWeatherService impatient = new CachingWeatherService(provider, new InMemoryWeatherCache(), shortTtl, clock);
        provider.willReturn(FIRST);
        impatient.currentWeather(City.SINGAPORE);
        clock.advance(shortTtl.plusMillis(1));
        provider.willReturn(SECOND);
        Contention contention = contend(impatient, 1);

        WeatherReport waiter = pool.submit(() -> impatient.currentWeather(City.SINGAPORE)).get(5, TimeUnit.SECONDS);

        assertThat(waiter.weather()).as("served without waiting for the slow refresh").isEqualTo(FIRST);
        assertThat(waiter.stale()).isTrue();
        provider.releaseCalls();
        assertThat(contention.requests().getFirst().get(5, TimeUnit.SECONDS).weather()).isEqualTo(SECOND);
    }

    @Test
    void aRequestThatWaitedOneTtlWithNothingCachedFails() throws Exception {
        Duration shortTtl = Duration.ofMillis(200);
        CachingWeatherService impatient = new CachingWeatherService(provider, new InMemoryWeatherCache(), shortTtl, clock);
        provider.willReturn(FIRST);
        Contention contention = contend(impatient, 1);

        Future<WeatherReport> waiter = pool.submit(() -> impatient.currentWeather(City.SINGAPORE));

        assertThatThrownBy(() -> waiter.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(WeatherUnavailableException.class)
                .cause()
                .hasMessage("gave up waiting for a refresh that is still in progress");
        provider.releaseCalls();
        assertThat(contention.requests().getFirst().get(5, TimeUnit.SECONDS).weather()).isEqualTo(FIRST);
    }

    @Test
    void anInterruptedWaiterTakesTheLastKnownValueImmediately() throws Exception {
        provider.willReturn(FIRST);
        patient.currentWeather(City.SINGAPORE);
        clock.advance(LONG_TTL.plusSeconds(1));
        provider.willReturn(SECOND);
        Contention contention = contend(patient, 2);
        int waiter = contention.waiters().getFirst();
        int leader = contention.leader();

        contention.thread(waiter).interrupt();

        WeatherReport report = contention.requests().get(waiter).get(5, TimeUnit.SECONDS);
        assertThat(report.weather()).isEqualTo(FIRST);
        assertThat(report.stale()).isTrue();
        provider.releaseCalls();
        assertThat(contention.requests().get(leader).get(5, TimeUnit.SECONDS).weather()).isEqualTo(SECOND);
    }

    @Test
    void rejectsANegativeTtl() {
        assertThatThrownBy(() -> new CachingWeatherService(provider, new InMemoryWeatherCache(), Duration.ofSeconds(-1), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The requests fired by {@link #contend}, with the thread running each one at the same index.
     * While the provider is held, the leader is parked on the provider's latch (untimed wait) and
     * every waiter is parked in its timed wait for the city lock.
     */
    private record Contention(List<Future<WeatherReport>> requests, AtomicReferenceArray<Thread> threads) {

        Thread thread(int index) {
            return threads.get(index);
        }

        int leader() {
            return indexesInState(Thread.State.WAITING).getFirst();
        }

        List<Integer> waiters() {
            return indexesInState(Thread.State.TIMED_WAITING);
        }

        private List<Integer> indexesInState(Thread.State state) {
            return IntStream.range(0, threads.length())
                    .filter(i -> threads.get(i) != null && threads.get(i).getState() == state)
                    .boxed()
                    .toList();
        }
    }

    /**
     * Holds the provider and fires {@code count} requests, returning once exactly one is inside the
     * provider and every other one is parked in its timed wait for the city lock; so the lock is
     * proven to be contended before the provider is released.
     */
    private Contention contend(CachingWeatherService target, int count) {
        provider.holdCalls();
        AtomicReferenceArray<Thread> threads = new AtomicReferenceArray<>(count);
        List<Future<WeatherReport>> requests = IntStream.range(0, count)
                .mapToObj(i -> pool.submit(() -> {
                    threads.set(i, Thread.currentThread());
                    return target.currentWeather(City.SINGAPORE);
                }))
                .toList();
        Contention contention = new Contention(requests, threads);
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> provider.inFlight() == 1 && contention.waiters().size() == count - 1);
        return contention;
    }
}
