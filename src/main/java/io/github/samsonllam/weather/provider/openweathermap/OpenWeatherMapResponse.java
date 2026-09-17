package io.github.samsonllam.weather.provider.openweathermap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The parts of an OpenWeatherMap current-weather payload this service reads.
 * Numbers are boxed so that a missing field fails loudly instead of reading as zero.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OpenWeatherMapResponse(Main main, Wind wind) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Main(Double temp) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Wind(Double speed) {
    }
}
