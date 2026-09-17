package io.github.samsonllam.weather.api;

import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.WeatherReport;
import io.github.samsonllam.weather.domain.WeatherService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/v1/weather", produces = MediaType.APPLICATION_JSON_VALUE)
public class WeatherController {

    /** Present (with value {@code true}) when the payload is older than the cache TTL because every provider was down. */
    public static final String STALE_HEADER = "X-Weather-Stale";

    /** Whole seconds since the observation was received from a provider. */
    public static final String AGE_HEADER = "X-Weather-Age";

    private final WeatherService weatherService;
    private final Clock clock;

    WeatherController(WeatherService weatherService, Clock clock) {
        this.weatherService = weatherService;
        this.clock = clock;
    }

    @GetMapping
    ResponseEntity<WeatherResponse> currentWeather(@RequestParam String city) {
        City resolved = City.fromQuery(city).orElseThrow(() -> new UnsupportedCityException(city));
        WeatherReport report = weatherService.currentWeather(resolved);
        long ageSeconds = Math.max(0, Duration.between(report.fetchedAt(), clock.instant()).toSeconds());
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                // This service applies its own freshness rules; downstream HTTP caches must not add theirs.
                .cacheControl(CacheControl.noStore())
                .header(AGE_HEADER, Long.toString(ageSeconds));
        if (report.stale()) {
            response.header(STALE_HEADER, "true");
        }
        return response.body(WeatherResponse.from(report.weather()));
    }
}
