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
(new OpenWeatherMap keys can take an hour or two to activate). Maven comes with the wrapper.

```bash
cp .env.example .env          # then put your two keys in .env
set -a; source .env; set +a   # export them into the shell

./mvnw verify                 # compile + unit, integration and Cucumber tests (about 10 s)
./mvnw spring-boot:run        # serve on http://localhost:8080

curl -i "http://localhost:8080/v1/weather?city=singapore"
```

With Docker instead of a local JDK:

```bash
docker build -t weather-service .
docker run --rm -p 8080:8080 --env-file .env weather-service
```

The service refuses to start if either key is missing; the error names the environment variable.
Keys are read from `WEATHERSTACK_ACCESS_KEY` and `OPENWEATHERMAP_API_KEY` (see
`src/main/resources/application.yml`), and they are never written to the logs.

## API

`GET /v1/weather?city=singapore`

| Response | When | Body |
|---|---|---|
| `200` | A result younger than 3 s is cached, or a provider answered | `{"wind_speed": 20, "temperature_degrees": 29}` |
| `200` + header `X-Weather-Stale: true` | Every provider failed, but an older result exists | Same shape, older data |
| `400` | `city` is missing or is not `singapore` | RFC 9457 problem detail |
| `503` | Every provider failed and nothing has ever been cached | RFC 9457 problem detail |

`temperature_degrees` is in degrees Celsius and `wind_speed` in kilometres per hour, both rounded
to whole numbers as in the example in the brief.

Operational endpoints (Spring Boot Actuator):

- `GET /actuator/health` includes a `weatherProviders` component showing each provider's circuit
  breaker state (`CLOSED`, `OPEN`, `HALF_OPEN`).
- `GET /actuator/metrics` includes `http.server.requests` and, per provider, `http.client.requests`.

## How it works

```
GET /v1/weather
      │
      ▼
WeatherController            400 for unknown cities, X-Weather-Stale header, 503
      │
      ▼
CachingWeatherService        3 s TTL, single-flight refresh, stale fallback
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

**Caching and stale results.** `CachingWeatherService` keeps one entry per city with the time it was
fetched. While the entry is at most `weather.cache-ttl` (3 s) old it is served as is. Once it is
older, the request refreshes it from the provider chain. If the chain fails and an entry exists, that
entry is served with `X-Weather-Stale: true`; the payload shape does not change, so clients that do
not care about staleness keep working. If the chain fails and nothing was ever cached, the response
is `503`. Entries are never evicted, so the stale fallback is always available after the first
successful call.

**Single-flight refresh.** When an entry expires under load, only one request performs the provider
call; concurrent requests for the same city wait on a per-city lock and reuse its result instead of
each calling the providers. There is a test for this (`CachingWeatherServiceTest`).

**Failover.** `FailoverWeatherProvider` tries the providers in order and returns the first
observation. Any exception from a provider counts as a failure, not only the expected
`ProviderException`, so a bug in one adapter cannot take the endpoint down. When all providers fail
it throws one exception with every individual failure attached.

**Circuit breakers.** Each provider is wrapped in its own Resilience4j circuit breaker. Once half of
the last 4 calls have failed (after at least 2 calls) the breaker opens for 30 s and the provider is
skipped immediately instead of costing every request a 2 s timeout before failover. After 30 s one
trial call is let through. The thresholds are in `application.yml` under `weather.circuit-breaker`.

**Providers.** Each adapter turns one upstream API into the domain `Weather` record (degrees Celsius,
km/h). Weatherstack already uses those units; it reports errors with HTTP 200 and an `error` object,
which the adapter treats as a failure. OpenWeatherMap is called with `units=metric`, which gives
Celsius but metres per second, so wind speed is multiplied by 3.6. Numeric fields are mapped to boxed
types so that a missing field is an error rather than a silent zero. Each provider has a 2 s connect
and read timeout. Error messages are rebuilt rather than copied from the HTTP client, because the
client's messages include the request URL, which carries the API key.

**Configuration.** Everything tunable is under `weather.*` in `application.yml`, bound to the
`WeatherProperties` record. Keys come from environment variables and are validated at startup.

## Tests

`./mvnw verify` runs 47 tests:

- **Unit tests** for the failover chain, the cache with its stale fallback and single-flight refresh
  (driven by a fake clock), the circuit-breaker wrapper and city parsing.
- **Provider tests** run each adapter against a real local HTTP server (`FakeProviderServer`, built on
  the JDK's `HttpServer`), covering payload parsing, Weatherstack's HTTP-200 error payloads, HTTP
  errors, a provider that hangs past the timeout, and an unreachable provider.
- **Web slice test** for the controller contract with MockMvc: exact JSON, rounding, the stale header,
  `400` and `503` problem details.
- **Application test** that the context wires up, and that startup fails with a clear message when a
  key is missing.
- **Cucumber scenarios** in `src/test/resources/features/weather.feature`, run by `CucumberTest`
  through the real HTTP endpoint against the whole application, with both providers replaced by local
  fake servers and the clock under test control: primary healthy, failover, 3 s caching, stale
  fallback, `503`, unknown city.

There are no mocks; the tests use small hand-written stubs and real HTTP, so they read as
documentation of the behaviour.

## Trade-offs and what was left out

- **In-memory cache rather than Redis.** One instance is enough for this brief. Several instances
  would each keep their own 3 s cache and their own stale copy, which is acceptable for weather data.
  If a shared cache were needed, `WeatherCache` is the seam: a Redis implementation plus a distributed
  single-flight lock (`SET NX`) would drop in without touching the service logic.
- **Synchronous refresh rather than stale-while-revalidate.** Serving the old value while refreshing in
  the background would return data older than 3 s while the providers are healthy, which the brief
  rules out. The cost is that the first request after expiry waits for the provider, bounded by the
  timeouts and the circuit breakers.
- **Wind speed is in km/h.** The brief names no unit; km/h is Weatherstack's default and matches the
  example value, and OpenWeatherMap is converted to it.
- **Values are rounded to whole numbers** to match the example payload. The domain model keeps the
  decimals; rounding happens only at the API boundary.
- **Only Singapore is supported**, as allowed by the brief. `City` is the one place to add another
  city; anything else returns `400` rather than being silently mapped.
- **Weatherstack is called over plain HTTP.** Its free plan does not offer HTTPS, so the access key
  travels in clear text to that provider. OpenWeatherMap is called over HTTPS.
- **Circuit-breaker thresholds are tuned for low traffic** (a window of 4 calls). At production
  volume a time-based sliding window would be the better choice; it is a configuration change.
- **Staleness is signalled with a custom header** rather than a different status code, so existing
  clients need no change and interested ones can still tell.
- **Health stays `UP` with open breakers.** The service still answers from the cache, so it should
  stay in rotation; the breaker states are exposed as health details for alerting.
- **No retries within a provider.** Failing over is the retry; retrying the same provider first would
  add latency for the client.
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
