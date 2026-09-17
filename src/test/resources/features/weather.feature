Feature: Singapore weather
  The service reports the current weather in Singapore. Weatherstack is the primary source,
  OpenWeatherMap takes over when Weatherstack fails, results are cached for up to 3 seconds,
  and the last known result keeps being served when every provider is down.

  Scenario: Weather comes from Weatherstack while it is healthy
    Given Weatherstack reports 29 degrees and wind 20 km/h
    And OpenWeatherMap reports 25.0 degrees and wind 1.0 m/s
    When a client asks for the weather in singapore
    Then the client receives temperature 29 and wind speed 20
    And the response is not marked stale
    And OpenWeatherMap was called 0 times

  Scenario: OpenWeatherMap takes over when Weatherstack is down
    Given Weatherstack is down
    And OpenWeatherMap reports 30.4 degrees and wind 5.0 m/s
    When a client asks for the weather in singapore
    Then the client receives temperature 30 and wind speed 18
    And the response is not marked stale

  Scenario: OpenWeatherMap takes over when Weatherstack rejects the access key
    Given Weatherstack rejects the access key
    And OpenWeatherMap reports 30.4 degrees and wind 5.0 m/s
    When a client asks for the weather in singapore
    Then the client receives temperature 30 and wind speed 18

  Scenario: Results are cached for up to 3 seconds
    Given Weatherstack reports 29 degrees and wind 20 km/h
    When a client asks for the weather in singapore
    And 2 seconds pass
    And a client asks for the weather in singapore
    Then Weatherstack was called 1 time
    And the response is 2 seconds old
    When 2 seconds pass
    And a client asks for the weather in singapore
    Then Weatherstack was called 2 times
    And the response is 0 seconds old

  Scenario: The last known weather is served as stale when every provider is down
    Given Weatherstack reports 29 degrees and wind 20 km/h
    And a client fetched the weather in singapore 60 seconds ago
    And Weatherstack is down
    And OpenWeatherMap is down
    When a client asks for the weather in singapore
    Then the client receives temperature 29 and wind speed 20
    And the response is marked stale
    And the response is 60 seconds old

  Scenario: Providers are asked at most once per 3 seconds while they are down
    Given Weatherstack reports 29 degrees and wind 20 km/h
    And a client fetched the weather in singapore 60 seconds ago
    And Weatherstack is down
    And OpenWeatherMap is down
    When a client asks for the weather in singapore
    And a client asks for the weather in singapore
    Then Weatherstack was called 2 times
    And OpenWeatherMap was called 1 time
    And the response is marked stale

  Scenario: A provider that keeps failing is skipped, then retried after 30 seconds
    Given Weatherstack is down
    And OpenWeatherMap reports 30.4 degrees and wind 5.0 m/s
    When a client asks for the weather in singapore 3 times, 4 seconds apart
    Then Weatherstack was called 3 times
    And the health endpoint reports weatherstack as OPEN
    When 4 seconds pass
    And a client asks for the weather in singapore
    Then Weatherstack was called 3 times
    And the client receives temperature 30 and wind speed 18
    When Weatherstack reports 29 degrees and wind 20 km/h
    And 31 seconds pass
    And a client asks for the weather in singapore
    Then the client receives temperature 29 and wind speed 20
    And Weatherstack was called 4 times
    And the health endpoint reports weatherstack as CLOSED

  Scenario: Nothing to serve when every provider is down and nothing was cached
    Given Weatherstack is down
    And OpenWeatherMap is down
    When a client asks for the weather in singapore
    Then the client receives status 503

  Scenario: Only Singapore is supported
    When a client asks for the weather in tokyo
    Then the client receives status 400
