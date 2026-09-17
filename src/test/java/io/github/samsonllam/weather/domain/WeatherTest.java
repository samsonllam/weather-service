package io.github.samsonllam.weather.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class WeatherTest {

    @ParameterizedTest
    @CsvSource({"-100, 0", "70, 500", "29.4, 20.1"})
    void acceptsPlausibleReadings(double temperature, double windSpeed) {
        assertThatCode(() -> new Weather(temperature, windSpeed)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-100.1, 70.1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsImplausibleTemperatures(double temperature) {
        assertThatThrownBy(() -> new Weather(temperature, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("implausible temperature");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 500.1, Double.NaN})
    void rejectsImplausibleWindSpeeds(double windSpeed) {
        assertThatThrownBy(() -> new Weather(25, windSpeed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("implausible wind speed");
    }

    @Test
    void windSpeedMayBeZero() {
        assertThatCode(() -> new Weather(25, 0)).doesNotThrowAnyException();
    }
}
