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
`src/main/resources/application.yml`), and they never appear in log messages or metrics.

## API

`GET /v1/weather?city=singapore`

| Response | When | Body |
|---|---|---|
| `200` | A result younger than 3 s is cached, or a provider answered | `{"wind_speed": 20, "temperature_degrees": 29}` |
| `200` + header `X-Weather-Stale: true` | Every provider failed, but an older result exists | Same shape, older data |
| `400` | `city` is missing or is not `singapore` | RFC 9457 problem detail |
| `503` | Every provider failed and nothing has ever been cached | RFC 9457 problem detail |

`temperature_degrees` is in degrees Celsius and `wind_speed` in kilometres per hour, both rounded
to whole numbers and in the field order of the example in the brief. Every `200` also carries an
`Age` header: the number of seconds since the observation was fetched from a provider.

Operational endpoints (Spring Boot Actuator):

- `GET /actuator/health` includes a `weatherProviders` component showing each provider's circuit
  breaker state (`CLOSED`, `OPEN`, `HALF_OPEN`).
- `GET /actuator/metrics` includes `http.server.requests` and `http.client.requests`, the latter tagged
  with the provider host (`client.name`) and the URI template, so the API keys never reach the metrics.

## How it works

```
GET /v1/weather
      │
      ▼
WeatherController            400 for unknown cities, Age and X-Weather-Stale headers, 503
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

**Caching and stale results.** `CachingWeatherService` keeps one entry per city with two timestamps:
when the value was fetched, and when the providers were last asked. While the last attempt is at
most `weather.cache-ttl` (3 s) old, the entry is served from memory. Once it is older, the request
asks the provider chain. On success the entry is replaced. On failure the old observation is kept,
its "last asked" time is moved forward, and it is served with `X-Weather-Stale: true` until the TTL
has passed again. So during an outage the providers are probed once per 3 s per city and every
other request is answered from memory, and the payload shape does not change, so clients that do
not care about staleness keep working. If nothing was ever cached, the response is `503`. Entries
are never evicted, so the stale fallback is always available after the first successful call.

**Single-flight refresh.** When an entry expires under load, only one request performs the provider
call; concurrent requests for the same city wait on a per-city lock and reuse its outcome, whether
that is a fresh value or a stale one. Both cases are tested with 16 concurrent virtual threads
(`CachingWeatherServiceTest`).

**Failover.** `FailoverWeatherProvider` tries the providers in order and returns the first
observation. Any exception from a provider counts as a failure, not only the expected
`ProviderException`, so a bug in one adapter cannot take the endpoint down. When all providers fail
it throws one exception with every individual failure attached. A provider skipped by its circuit
breaker is logged at debug level only, so an outage does not flood the log.

**Circuit breakers.** Each provider is wrapped in its own Resilience4j circuit breaker. The breaker
looks at the last 6 calls and, once it has seen at least 3, opens when half or more of them failed.
While open, the provider is skipped immediately instead of costing the request a timeout before
failover. After 30 s the next request is let through as a trial call: the breaker closes if it
succeeds and reopens if it fails. Recovery happens on the next request after the wait, not on a
timer, so `/actuator/health` shows `OPEN` until then. The thresholds are in `application.yml` under
`weather.circuit-breaker`, and the breakers read time through the application `Clock` so the whole
open-retry-close cycle is covered by tests without waiting.

**Latency bounds.** Each provider has a 2 s connect timeout and a 2 s read timeout. The worst case
for the first request after the cache expires, with both providers accepting connections and then
hanging, is 4 s per provider and 8 s end to end before a stale result or a `503`. That request pays
the price once: after three such failures per provider the breakers open and the answer is immediate.

**Providers.** Each adapter turns one upstream API into the domain `Weather` record (degrees Celsius,
km/h). Weatherstack is called with `units=m`, which already gives those units; it reports errors with
HTTP 200 and an `error` object, which the adapter treats as a failure. OpenWeatherMap is called with
`units=metric`, which gives Celsius but metres per second, so wind speed is multiplied by 3.6.
Numeric fields are mapped to boxed types so that a missing field is an error rather than a silent
zero, and `Weather` rejects physically implausible readings, so a provider glitch cannot become the
value served for the whole of an outage. Error messages are rebuilt rather than copied from the HTTP
client so that they stay short and provider-specific; Spring already strips the query string, and
with it the key, from its own messages, so this is defence in depth, and the tests check the full
stack trace for the key.

**Configuration.** Everything tunable is under `weather.*` in `application.yml`, bound to the
`WeatherProperties` record. Keys come from environment variables and are validated at startup.

## Tests

`./mvnw verify` runs 76 tests:

- **Unit tests** for the failover chain, the cache with its stale fallback and single-flight refresh
  (driven by a fake clock), the circuit-breaker wrapper including recovery, value validation and
  city parsing.
- **Provider tests** run each adapter against a real local HTTP server (`FakeProviderServer`, built on
  the JDK's `HttpServer`), covering payload parsing, Weatherstack's HTTP-200 error payloads, HTTP
  errors, non-JSON bodies, implausible readings, a provider that hangs before or after sending its
  headers, and an unreachable provider.
- **Web slice test** for the controller contract with MockMvc: exact JSON, rounding, the `Age` and
  stale headers, `400` and `503` problem details.
- **Configuration tests** that the context wires up and that startup fails with a clear message when
  a key or a setting is missing.
- **Cucumber scenarios** in `src/test/resources/features/weather.feature`, run by `CucumberTest`
  through the real HTTP endpoint against the whole application, with both providers replaced by local
  fake servers and the clock under test control: primary healthy, failover on an outage and on a
  rejected key, 3 s caching, stale fallback, one probe per TTL during an outage, a breaker opening
  and recovering (checked through `/actuator/health`), `503`, unknown city.

There are no mocks; the tests use small hand-written stubs and real HTTP, so they read as
documentation of the behaviour.

## Trade-offs and what was left out

- **In-memory cache rather than Redis.** One instance is enough for this brief. Several instances
  would each keep their own 3 s cache and their own stale copy, which is acceptable for weather data.
  If a shared cache were needed, `WeatherCache` is the seam: a Redis implementation plus a distributed
  single-flight lock (`SET NX`) would drop in without touching the service logic.
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
- **Staleness is signalled with a custom header** rather than a different status code or
  `Cache-Control: stale-if-error`, which governs downstream HTTP caches rather than this server. The
  `Age` header uses the standard meaning.
- **Circuit-breaker thresholds are tuned for low traffic**: with one refresh per 3 s, a window of 6
  calls spans about 18 s, so a short blip of 3 failures benches a provider for 30 s. Traffic simply
  moves to the other provider. At production volume a time-based sliding window would be the better
  choice; it is a configuration change.
- **The served value may flip between providers** when Weatherstack fails once and recovers, because
  the two services do not report identical readings. The brief allows it.
- **Health stays `UP` with open breakers.** The service still answers from the cache, so it should
  stay in rotation; the breaker states are exposed as health details for alerting.
- **No retries within a provider.** Failing over is the retry; retrying the same provider first would
  add latency for the client.
- **Weatherstack's free plan allows 100 calls a month.** Under steady traffic a 3 s TTL exhausts that
  within minutes, after which Weatherstack answers HTTP 200 with an error payload; the service treats
  that as a failure and lives on OpenWeatherMap, which is visible in the logs and in the breaker
  state. Both providers are called over HTTPS.
- **Tracing is not enabled.** If Micrometer Tracing were added, the HTTP client observation would
  record the expanded request URL, key included, as a span attribute; that attribute would need
  filtering first.
- **Not included:** authentication and rate limiting on the endpoint, an OpenAPI document, and a
  smoke test against the live provider APIs.

## What I would do with more time

- A Redis-backed `WeatherCache` with a distributed single-flight lock, for running several instances.
- A background refresh shortly before expiry while traffic is steady, keeping tail latency flat
  without ever serving data older than the TTL.
- Observability: metrics for cache hits, misses and stale responses, Resilience4j's Micrometer
  binding, structured logging, and an alert on breakers staying open.
- Contract tests built from recorded provider responses, and a profile-gated smoke test against the
  live APIs.
- `Retry-After` on `503`, an OpenAPI description, and per-client rate limiting.
- A layered Docker image and container-aware JVM settings.
