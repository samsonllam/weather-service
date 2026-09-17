package io.github.samsonllam.weather.api;

import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.WeatherReport;
import io.github.samsonllam.weather.domain.WeatherService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/v1/weather", produces = MediaType.APPLICATION_JSON_VALUE)
class WeatherController {

    /** Present (with value {@code true}) when the payload is older than the cache TTL because every provider was down. */
    static final String STALE_HEADER = "X-Weather-Stale";

    private final WeatherService weatherService;

    WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping
    ResponseEntity<WeatherResponse> currentWeather(@RequestParam String city) {
        City resolved = City.fromQuery(city).orElseThrow(() -> new UnsupportedCityException(city));
        WeatherReport report = weatherService.currentWeather(resolved);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (report.stale()) {
            response.header(STALE_HEADER, "true");
        }
        return response.body(WeatherResponse.from(report.weather()));
    }
}
