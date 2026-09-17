package io.github.samsonllam.weather.provider.weatherstack;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The parts of a Weatherstack {@code /current} payload this service reads. Weatherstack reports
 * failures with HTTP 200 and an {@code error} object, so that object is mapped too.
 * Numbers are boxed so that a missing field fails loudly instead of reading as zero.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record WeatherstackResponse(Current current, Error error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Current(Double temperature, @JsonProperty("wind_speed") Double windSpeed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(Integer code, String type, String info) {
    }
}
