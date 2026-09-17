package io.github.samsonllam.weather.provider.openweathermap;

import io.github.samsonllam.weather.domain.City;
import io.github.samsonllam.weather.domain.ProviderException;
import io.github.samsonllam.weather.domain.Weather;
import io.github.samsonllam.weather.domain.WeatherProvider;
import io.github.samsonllam.weather.provider.ProviderSupport;
import org.springframework.web.client.RestClient;

/**
 * OpenWeatherMap current-weather API. Requested with {@code units=metric}, which yields degrees
 * Celsius but metres per second for wind, so wind speed is converted to kilometres per hour.
 *
 * @see <a href="https://openweathermap.org/current">OpenWeatherMap documentation</a>
 */
public final class OpenWeatherMapProvider implements WeatherProvider {

    public static final String NAME = "openweathermap";

    private static final double METRES_PER_SECOND_TO_KPH = 3.6;

    private final RestClient restClient;
    private final String apiKey;

    /** @param restClient client whose base URL points at the OpenWeatherMap API root */
    public OpenWeatherMapProvider(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Weather currentWeather(City city) {
        String query = city.displayName() + "," + city.countryCode();
        OpenWeatherMapResponse response = ProviderSupport.call(NAME, () -> restClient.get()
                .uri("/data/2.5/weather?q={query}&appid={apiKey}&units=metric", query, apiKey)
                .retrieve()
                .body(OpenWeatherMapResponse.class));
        if (response.main() == null || response.main().temp() == null
                || response.wind() == null || response.wind().speed() == null) {
            throw new ProviderException(NAME, "response is missing temperature or wind speed");
        }
        return ProviderSupport.weather(NAME, response.main().temp(), response.wind().speed() * METRES_PER_SECOND_TO_KPH);
    }
}
