package io.github.samsonllam.weather.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CityTest {

    @ParameterizedTest
    @ValueSource(strings = {"singapore", "Singapore", "SINGAPORE", "  singapore "})
    void resolvesSingaporeIgnoringCaseAndWhitespace(String query) {
        assertThat(City.fromQuery(query)).contains(City.SINGAPORE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tokyo", "", "SG"})
    void rejectsAnythingElse(String query) {
        assertThat(City.fromQuery(query)).isEmpty();
    }

    @Test
    void rejectsNull() {
        assertThat(City.fromQuery(null)).isEmpty();
    }
}
