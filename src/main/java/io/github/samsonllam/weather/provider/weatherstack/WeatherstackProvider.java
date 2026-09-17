package io.github.samsonllam.weather.provider.weatherstack;

import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.ProviderException;
import io.github.samsonllam.weather.domain.Weather;
import io.github.samsonllam.weather.domain.WeatherProvider;
import io.github.samsonllam.weather.provider.ProviderSupport;
import org.springframework.web.client.RestClient;

/**
 * Weatherstack current-weather API, requested with {@code units=m} (metric), which reports degrees
 * Celsius and kilometres per hour, so no conversion is needed.
 *
 * @see <a href="https://weatherstack.com/documentation">Weatherstack documentation</a>
 */
public final class WeatherstackProvider implements WeatherProvider {

    public static final String NAME = "weatherstack";

    private final RestClient restClient;
    private final String accessKey;

    /** @param restClient client whose base URL points at the Weatherstack API root */
    public WeatherstackProvider(RestClient restClient, String accessKey) {
        this.restClient = restClient;
        this.accessKey = accessKey;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Weather currentWeather(City city) {
        WeatherstackResponse response = ProviderSupport.call(NAME, () -> restClient.get()
                .uri("/current?access_key={accessKey}&query={query}&units=m", accessKey, city.displayName())
                .retrieve()
                .body(WeatherstackResponse.class));
        if (response.error() != null) {
            throw new ProviderException(NAME, "API error " + response.error().code()
                    + " (" + response.error().type() + "): " + response.error().info());
        }
        WeatherstackResponse.Current current = response.current();
        if (current == null || current.temperature() == null || current.windSpeed() == null) {
            throw new ProviderException(NAME, "response is missing current temperature or wind speed");
        }
        return ProviderSupport.weather(NAME, current.temperature(), current.windSpeed());
    }
}
