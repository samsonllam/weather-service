package io.github.samsonllam.weather.support;

/** Trimmed but structurally faithful copies of real provider responses. */
public final class ProviderPayloads {

    private ProviderPayloads() {
    }

    public static String weatherstack(double temperature, double windSpeed) {
        return """
                {
                  "request": {"type": "City", "query": "Singapore, Singapore", "language": "en", "unit": "m"},
                  "location": {"name": "Singapore", "country": "Singapore", "region": "", "lat": "1.293", "lon": "103.856",
                               "timezone_id": "Asia/Singapore", "localtime": "2026-09-17 12:00", "utc_offset": "8.0"},
                  "current": {"observation_time": "04:00 AM", "temperature": %s, "weather_code": 116,
                              "weather_descriptions": ["Partly cloudy"], "wind_speed": %s, "wind_degree": 170,
                              "wind_dir": "S", "pressure": 1010, "precip": 0, "humidity": 70, "cloudcover": 50,
                              "feelslike": 34, "uv_index": 7, "visibility": 10, "is_day": "yes"}
                }
                """.formatted(temperature, windSpeed);
    }

    public static final String WEATHERSTACK_INVALID_KEY = """
            {
              "success": false,
              "error": {"code": 101, "type": "invalid_access_key", "info": "You have not supplied a valid API Access Key."}
            }
            """;

    public static String openWeatherMap(double temperatureCelsius, double windMetresPerSecond) {
        return """
                {
                  "coord": {"lon": 103.8501, "lat": 1.2897},
                  "weather": [{"id": 802, "main": "Clouds", "description": "scattered clouds", "icon": "03d"}],
                  "base": "stations",
                  "main": {"temp": %s, "feels_like": 36.2, "temp_min": 29.1, "temp_max": 31.7, "pressure": 1010, "humidity": 70},
                  "visibility": 10000,
                  "wind": {"speed": %s, "deg": 170},
                  "clouds": {"all": 40},
                  "dt": 1758081600,
                  "sys": {"country": "SG", "sunrise": 1758063000, "sunset": 1758106500},
                  "timezone": 28800, "id": 1880252, "name": "Singapore", "cod": 200
                }
                """.formatted(temperatureCelsius, windMetresPerSecond);
    }

    public static final String OPENWEATHERMAP_INVALID_KEY = """
            {"cod": 401, "message": "Invalid API key. Please see https://openweathermap.org/faq#error401 for more info."}
            """;
}
