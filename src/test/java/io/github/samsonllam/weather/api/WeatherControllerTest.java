package io.github.samsonllam.weather.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.ProviderException;
import io.github.samsonllam.weather.domain.Weather;
import io.github.samsonllam.weather.domain.WeatherReport;
import io.github.samsonllam.weather.domain.WeatherService;
import io.github.samsonllam.weather.domain.WeatherUnavailableException;
import java.time.Instant;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WeatherController.class)
class WeatherControllerTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-09-17T04:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubWeatherService weatherService;

    @Test
    void returnsTheUnifiedPayloadForSingapore() throws Exception {
        weatherService.respondWith(new WeatherReport(new Weather(29, 20), FETCHED_AT, false));

        mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"wind_speed\": 20, \"temperature_degrees\": 29}", JsonCompareMode.STRICT))
                .andExpect(header().doesNotExist(WeatherController.STALE_HEADER));
    }

    @Test
    void roundsToWholeNumbers() throws Exception {
        weatherService.respondWith(new WeatherReport(new Weather(30.4, 18.0000001), FETCHED_AT, false));

        mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"wind_speed\": 18, \"temperature_degrees\": 30}", JsonCompareMode.STRICT));
    }

    @Test
    void flagsStaleResultsWithAHeaderAndKeepsThePayloadUnchanged() throws Exception {
        weatherService.respondWith(new WeatherReport(new Weather(29, 20), FETCHED_AT, true));

        mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                .andExpect(status().isOk())
                .andExpect(header().string(WeatherController.STALE_HEADER, "true"))
                .andExpect(content().json("{\"wind_speed\": 20, \"temperature_degrees\": 29}", JsonCompareMode.STRICT));
    }

    @Test
    void rejectsUnsupportedCities() throws Exception {
        mockMvc.perform(get("/v1/weather").param("city", "tokyo"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unsupported city"))
                .andExpect(jsonPath("$.detail").value("Unsupported city 'tokyo'. Supported cities: singapore"));
    }

    @Test
    void rejectsAMissingCityParameter() throws Exception {
        mockMvc.perform(get("/v1/weather"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void answers503WhenNoProviderAndNoCacheCanServe() throws Exception {
        weatherService.failWith(city -> new WeatherUnavailableException(city, new ProviderException("failover", "all down")));

        mockMvc.perform(get("/v1/weather").param("city", "singapore"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Weather unavailable"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubServiceConfiguration {

        @Bean
        StubWeatherService weatherService() {
            return new StubWeatherService();
        }
    }

    static class StubWeatherService implements WeatherService {

        private Function<City, WeatherReport> behaviour = city -> {
            throw new IllegalStateException("no response scripted");
        };

        void respondWith(WeatherReport report) {
            behaviour = city -> report;
        }

        void failWith(Function<City, RuntimeException> failure) {
            behaviour = city -> {
                throw failure.apply(city);
            };
        }

        @Override
        public WeatherReport currentWeather(City city) {
            return behaviour.apply(city);
        }
    }
}
