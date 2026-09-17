# weather-service

An HTTP service that reports the current weather in Singapore. It reads from
[Weatherstack](https://weatherstack.com/documentation) first, fails over to
[OpenWeatherMap](https://openweathermap.org/current) when Weatherstack cannot answer, caches results
for up to 3 seconds, and keeps serving the last known result when every provider is down.

```
$ curl "http://localhost:8080/v1/weather?city=singapore"
{"wind_speed":20,"temperature_degrees":29}
```

Java 25, Spring Boot 4.0, Maven. Virtual threads are on, so blocking provider calls do not tie up
platform threads.

## Running it locally

You need a JDK 25 and two API keys: a Weatherstack access key and an OpenWeatherMap API key
(new OpenWeatherMap keys can take an hour or two to activate). The Maven wrapper downloads Maven
3.9.15 on first use. Java 25 is the current LTS and the target runtime; the code itself uses
nothing newer than Java 21, so `java.version` in `pom.xml` is the only thing to change to build on 21.

```bash
cp .env.example .env          # then put your two keys in .env
set -a; source .env; set +a   # export them into the shell

./mvnw verify                 # compile + unit, integration and Cucumber tests (about 15 s)
./mvnw spring-boot:run        # serve on http://localhost:8080

curl -i "http://localhost:8080/v1/weather?city=singapore"
```

To run a single test class: `./mvnw test -Dtest=CachingWeatherServiceTest`.

With Docker instead of a local JDK:

```bash
docker build -t weather-service .
docker run --rm -p 8080:8080 --env-file .env weather-service
```

The service refuses to start if either key is missing; the error names the environment variable.
Keys are read from `WEATHERSTACK_ACCESS_KEY` and `OPENWEATHERMAP_API_KEY` (see
`src/main/resources/application.yml`). They are sent only in the provider requests: log messages,
exception chains and metrics are built from status codes, error codes and class names, never from
upstream text, so a provider that echoed the key back could not put it into the logs.

## API

`GET /v1/weather?city=singapore`

| Response | When | Body |
|---|---|---|
| `200` | A result younger than 3 s is cached, or a provider answered | `{"wind_speed": 20, "temperature_degrees": 29}` |
| `200` + header `X-Weather-Stale: true` | Every provider failed, but an older result exists | Same shape, older data |
| `400` | `city` is missing or is not `singapore` | RFC 9457 problem detail |
| `503` | Every provider failed and nothing has ever been cached | RFC 9457 problem detail |

`temperature_degrees` is in degrees Celsius and `wind_speed` in kilometres per hour, both rounded
to the nearest whole number (halves round up). Every `200` also carries `X-Weather-Age`, the whole
seconds since the observation was received from a provider, and `Cache-Control: no-store`, because
this service applies its own freshness rules and downstream caches must not add theirs.

Operational endpoints (Spring Boot Actuator):

- `GET /actuator/health` includes a `weatherProviders` component with each provider's circuit
  breaker state (`CLOSED`, `OPEN`, `HALF_OPEN`) and, per city, whether an observation is cached.
- `GET /actuator/metrics` includes `http.server.requests` and `http.client.requests`, the latter tagged
  with the provider host (`client.name`) and the URI template, so the API keys never reach the metrics.

## How it works

```
GET /v1/weather
      │
      ▼
WeatherController            400 for unknown cities, X-Weather-Age and X-Weather-Stale headers, 503
      │
      ▼
CachingWeatherService        asks the providers at most once per 3 s, single-flight, stale fallback
      │        ▲
      │        └── InMemoryWeatherCache (one entry per city, never evicted)
      ▼
FailoverWeatherProvider      first provider that answers wins
      │
      ├─▶ CircuitBreakingWeatherProvider ─▶ WeatherstackProvider     (primary)
      └─▶ CircuitBreakingWeatherProvider ─▶ OpenWeatherMapProvider   (failover)
```

Every box is a small class behind the `WeatherProvider` or `WeatherService` interface, so each
behaviour is tested on its own and can be changed on its own. The wiring, including the failover
order, lives in one place: `WeatherConfiguration`.

**Caching and stale results.** `CachingWeatherService` records the outcome of every refresh
attempt with its completion time, and keeps one observation per city with the time it was received.
While the last attempt is at most `weather.cache-ttl` (3 s) old, requests are answered from memory:
the cached observation, or a `503` if that attempt failed and nothing was ever cached. Once the last
attempt is older than 3 s, the next request asks the provider chain; on success the observation is
replaced, on failure the old one is kept and served with `X-Weather-Stale: true`. So during an
outage the providers are probed once per 3 s per city and every other request is answered from
memory, cold start included. The payload shape does not change for stale results, so clients that
do not care about staleness keep working. Observations are never evicted, so the stale fallback is
available after the first successful call for the lifetime of the process; a restart or a new
replica starts empty.

**Single-flight refresh.** When an entry expires under load, only one request performs the provider
call; concurrent requests for the same city wait on a per-city lock and then take the answer that
call produced, whether it was a fresh value, a stale one or a `503`. A request that has waited one
TTL for someone else's refresh stops waiting and takes the last known value instead, so a slow
provider never queues customers behind it. These cases are tested with 16 concurrent virtual
threads, including a refresh that takes longer than the TTL (`CachingWeatherServiceTest`).

**Failover.** `FailoverWeatherProvider` tries the providers in order and returns the first
observation. Any `RuntimeException` from a provider counts as a failure, not only the expected
`ProviderException`, so a bug in one adapter cannot take the endpoint down. When all providers fail
it throws one exception with every individual failure attached. A provider skipped by its circuit
breaker is logged at debug level only; an outage therefore costs one warning per probe, that is one
per 3 s per city.

**Circuit breakers.** Each provider is wrapped in its own Resilience4j circuit breaker. The breaker
looks at the last 6 calls and, once it has seen at least 3, opens when half or more of them failed.
While open, the provider is skipped immediately instead of costing the request a timeout before
failover. After 30 s the next call is let through as a trial: the breaker closes if it succeeds and
reopens if it fails. That trial only happens when a request actually reaches the provider chain,
which needs both the 30 s to have passed and the cache's own 3 s suppression to have expired, so
`/actuator/health` shows `OPEN` until then. The thresholds are in `application.yml` under
`weather.circuit-breaker`; the window type (count-based) is set in `WeatherConfiguration`. The
breakers read time through the application `Clock`, so the whole open-retry-close cycle is covered
by tests without waiting.

**Latency bounds.** Each provider has a 2 s timeout, applied by Spring as the JDK request timeout,
which bounds connecting and receiving the response headers together, and applied again to reading
the body. A provider that hangs costs about 2 s; one that sends headers and then stalls the body
costs up to 4 s. The first request after the cache expires therefore waits about 4 s in the worst
common case (both providers hanging) before it gets a stale result or a `503`, and it pays that
price once: after three such failures per provider the breakers open and the answer is immediate.
Requests waiting behind that refresh give up after 3 s and take the last known value.

**Providers.** Each adapter turns one upstream API into the domain `Weather` record (degrees Celsius,
km/h). Weatherstack is called with `units=m`, which already gives those units; it reports errors with
HTTP 200 and an `error` object, and the adapter maps its numeric code to a description of its own
rather than repeating the upstream text. OpenWeatherMap is called with `units=metric`, which gives
Celsius but metres per second, so wind speed is multiplied by 3.6. Numeric fields are mapped to
boxed types so that a missing field is an error rather than a silent zero, and `Weather` rejects
physically implausible readings, so a provider glitch cannot become the value served for the whole
of an outage. HTTP errors and undecodable bodies become a `ProviderException` that carries the
status or the exception class name only; the Spring exception, whose message quotes the body, is
deliberately not kept as the cause. The tests put the key into error bodies and check the full
stack trace for it.

**Configuration.** Everything tunable is under `weather.*` in `application.yml`, bound to the
`WeatherProperties` record. Keys come from environment variables and are validated at startup.

## Tests

`./mvnw verify` runs 87 tests:

- **Unit tests** for the failover chain, the cache with its stale fallback, once-per-TTL probing and
  single-flight refresh in every outcome (driven by a fake clock), the circuit-breaker wrapper
  including recovery, value validation and city parsing.
- **Provider tests** run each adapter against a real local HTTP server (`FakeProviderServer`, built on
  the JDK's `HttpServer`), covering payload parsing, Weatherstack's HTTP-200 error payloads, HTTP
  errors, non-JSON and empty bodies, implausible readings, a provider that hangs before or after
  sending its headers, an unreachable provider, and error bodies that echo the key.
- **Web slice test** for the controller contract with MockMvc: exact JSON, rounding, the age and
  stale headers, `400` and `503` problem details.
- **Configuration tests** that the context wires up and that startup fails with a clear message when
  a key or a setting is missing.
- **Cucumber scenarios** in `src/test/resources/features/weather.feature`, run by `CucumberTest`
  through the real HTTP endpoint against the whole application, with both providers replaced by local
  fake servers and the clock under test control: primary healthy, failover on an outage, on a
  rejected key and on a hanging primary, 3 s caching, stale fallback with providers down or hanging,
  a fallback value surviving a later total outage, one probe per TTL during an outage, a breaker
  opening and recovering (checked through `/actuator/health`), a stale value being replaced on
  recovery, `503` on a cold start, unknown city, and key-free metrics.

There are no mocks; the tests use small hand-written stubs and real HTTP, so they read as
documentation of the behaviour.

## Trade-offs and what was left out

- **In-memory cache and per-instance coordination.** One instance is enough for this brief. Several
  instances would each keep their own cache, lock and attempt record, so an outage costs one probe
  per 3 s per instance, which is acceptable for weather data. `WeatherCache` is the seam for a shared
  store such as Redis, but a cluster-wide single-flight also needs a shared lock with a lease and a
  fencing token; that is coordination logic in the service, not a drop-in cache swap.
- **Synchronous refresh rather than stale-while-revalidate.** Serving the old value while refreshing in
  the background would return data older than 3 s while the providers are healthy, which the brief
  rules out. The cost is that the first request after expiry waits for the provider, within the
  bounds above.
- **Resilience4j's core library rather than its Spring Boot starter.** The decorator is plain Java
  that can be stepped through and unit-tested with a hand-built breaker, and the behaviour is visible
  in the wiring rather than in an annotation. The starter would add breaker metrics and health for
  free; that is a fair trade to revisit.
- **Wind speed is in km/h.** The brief names no unit; km/h is what Weatherstack's metric setting
  returns and it matches the example value, and OpenWeatherMap is converted to it.
- **Values are rounded to whole numbers** to match the example payload. The domain model keeps the
  decimals; rounding happens only at the API boundary.
- **Only Singapore is supported**, as allowed by the brief. `City` is the one place to add another
  city; anything else returns `400` rather than being silently mapped.
- **Staleness and age use custom headers** (`X-Weather-Stale`, `X-Weather-Age`) rather than a
  different status code or HTTP cache metadata: the age counted is application data age, not the
  RFC 9111 `Age` of a cached response, and `Cache-Control: no-store` keeps downstream caches out of it.
- **Freshness is judged on the wall clock.** A backward clock step extends freshness by the size of
  the step; a monotonic time source would avoid that. Clock anomalies cannot prevent the stale
  fallback, because nothing in the cache path depends on timestamps being ordered.
- **Circuit-breaker thresholds are tuned for one probe per 3 s**: a window of 6 calls spans about
  18 s, so three failures in a row bench a provider for 30 s and traffic moves to the other one. The
  cache gates the provider call rate, so endpoint traffic does not change these numbers.
- **The served value may flip between providers** when Weatherstack fails once and recovers, because
  the two services do not report identical readings. The brief allows it.
- **Health stays `UP` with open breakers**, and even on a cold start with nothing cached: liveness is
  about the process, and taking a stale-serving instance out of rotation would defeat the point.
  The details show the breaker states and whether a cached value exists, for alerting; a breaker
  state is the breaker's view of its recent calls, not a live probe of the upstream.
- **No retries within a provider.** Failing over is the retry; retrying the same provider first would
  add latency for the client.
- **Weatherstack's free plan allows 100 calls a month.** Under steady traffic a 3 s TTL exhausts that
  within minutes, after which Weatherstack answers HTTP 200 with an error payload; the service treats
  that as a failure and lives on OpenWeatherMap, which is visible in the logs and in the breaker
  state. Both providers are called over HTTPS.
- **Tracing is not enabled.** If Micrometer Tracing were added, the HTTP client observation would
  record the expanded request URL, key included, as a span attribute; that attribute would need
  filtering first.
- **Not included:** authentication and rate limiting on the endpoint, an overall request deadline
  beyond the provider timeouts and the one-TTL wait, an OpenAPI document, and a smoke test against
  the live provider APIs.

## What I would do with more time

- A shared cache and a leased, fenced refresh lock, for running several instances.
- A background refresh shortly before expiry while traffic is steady, keeping tail latency flat
  without ever serving data older than the TTL.
- Observability: metrics for cache hits, misses and stale responses, Resilience4j's Micrometer
  binding, structured logging, and an alert on breakers staying open.
- Contract tests built from recorded provider responses, and a profile-gated smoke test against the
  live APIs.
- `Retry-After` on `503`, an OpenAPI description, and per-client rate limiting.
- A layered Docker image and container-aware JVM settings.

## Tooling

I used Claude Code as a pair programmer for scaffolding and test boilerplate, as I do day to day;
the design, the trade-offs above and the final review are mine.
