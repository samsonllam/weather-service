package io.github.samsonllam.weather.bdd;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.samsonllam.weather.api.WeatherController;
import io.github.samsonllam.weather.domain.WeatherCache;
import io.github.samsonllam.weather.support.FakeProviderServer;
import io.github.samsonllam.weather.support.MutableClock;
import io.github.samsonllam.weather.support.ProviderPayloads;
import java.time.Duration;
import org.json.JSONException;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

public class WeatherSteps {

    private final FakeProviderServer weatherstack = CucumberSpringConfiguration.WEATHERSTACK;
    private final FakeProviderServer openWeatherMap = CucumberSpringConfiguration.OPENWEATHERMAP;

    @LocalServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    @Autowired
    private WeatherCache cache;

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    private ResponseEntity<String> lastResponse;

    @Before
    public void startFromAKnownState() {
        cache.clear();
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        weatherstack.reset();
        openWeatherMap.reset();
        lastResponse = null;
    }

    @Given("Weatherstack reports {int} degrees and wind {int} km\\/h")
    public void weatherstackReports(int temperature, int windSpeed) {
        weatherstack.respond(200, ProviderPayloads.weatherstack(temperature, windSpeed));
    }

    @Given("OpenWeatherMap reports {double} degrees and wind {double} m\\/s")
    public void openWeatherMapReports(double temperature, double windSpeed) {
        openWeatherMap.respond(200, ProviderPayloads.openWeatherMap(temperature, windSpeed));
    }

    @Given("Weatherstack is down")
    public void weatherstackIsDown() {
        weatherstack.respond(503, "{}");
    }

    @Given("Weatherstack rejects the access key")
    public void weatherstackRejectsTheAccessKey() {
        weatherstack.respond(200, ProviderPayloads.WEATHERSTACK_INVALID_KEY);
    }

    @Given("OpenWeatherMap is down")
    public void openWeatherMapIsDown() {
        openWeatherMap.respond(503, "{}");
    }

    @Given("a client fetched the weather in {word} {int} seconds ago")
    public void aClientFetchedTheWeatherSecondsAgo(String city, int seconds) {
        requestWeather(city);
        assertThat(lastResponse.getStatusCode().value()).isEqualTo(200);
        clock.advance(Duration.ofSeconds(seconds));
    }

    @When("{int} seconds pass")
    public void secondsPass(int seconds) {
        clock.advance(Duration.ofSeconds(seconds));
    }

    @When("a client asks for the weather in {word}")
    public void aClientAsksForTheWeatherIn(String city) {
        requestWeather(city);
    }

    @When("a client asks for the weather in {word} {int} times, {int} seconds apart")
    public void aClientAsksForTheWeatherRepeatedly(String city, int times, int secondsApart) {
        for (int i = 0; i < times; i++) {
            if (i > 0) {
                clock.advance(Duration.ofSeconds(secondsApart));
            }
            requestWeather(city);
        }
    }

    @Then("the client receives temperature {int} and wind speed {int}")
    public void theClientReceivesTemperatureAndWindSpeed(int temperature, int windSpeed) throws JSONException {
        assertThat(lastResponse.getStatusCode().value()).isEqualTo(200);
        JSONAssert.assertEquals(
                "{\"wind_speed\": %d, \"temperature_degrees\": %d}".formatted(windSpeed, temperature),
                lastResponse.getBody(), JSONCompareMode.STRICT);
    }

    @Then("the client receives status {int}")
    public void theClientReceivesStatus(int status) {
        assertThat(lastResponse.getStatusCode().value()).isEqualTo(status);
    }

    @Then("the response is marked stale")
    public void theResponseIsMarkedStale() {
        assertThat(lastResponse.getHeaders().getFirst(WeatherController.STALE_HEADER)).isEqualTo("true");
    }

    @Then("the response is not marked stale")
    public void theResponseIsNotMarkedStale() {
        assertThat(lastResponse.getHeaders().containsHeader(WeatherController.STALE_HEADER)).isFalse();
    }

    @Then("the response is {int} seconds old")
    public void theResponseIsSecondsOld(int seconds) {
        assertThat(lastResponse.getHeaders().getFirst("Age")).isEqualTo(Integer.toString(seconds));
    }

    @Then("Weatherstack was called {int} time(s)")
    public void weatherstackWasCalledTimes(int times) {
        assertThat(weatherstack.requestCount()).isEqualTo(times);
    }

    @Then("OpenWeatherMap was called {int} time(s)")
    public void openWeatherMapWasCalledTimes(int times) {
        assertThat(openWeatherMap.requestCount()).isEqualTo(times);
    }

    @Then("the health endpoint reports {word} as {word}")
    public void theHealthEndpointReportsProviderAs(String provider, String state) {
        String health = RestClient.create(baseUrl()).get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(JsonPath.<String>read(health, "$.status")).isEqualTo("UP");
        assertThat(JsonPath.<String>read(health, "$.components.weatherProviders.details." + provider)).isEqualTo(state);
    }

    private void requestWeather(String city) {
        lastResponse = RestClient.create(baseUrl())
                .get()
                .uri("/v1/weather?city={city}", city)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) -> { })
                .toEntity(String.class);
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}
