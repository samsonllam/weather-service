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
    And the health endpoint shows the cache for singapore as cached

  Scenario: OpenWeatherMap takes over when Weatherstack is down
    Given Weatherstack is down
    And OpenWeatherMap reports 30.4 degrees and wind 5.0 m/s
    When a client asks for the weather in singapore
    Then the client receives temperature 30 and wind speed 18
    And the response is not marked stale
    And the metrics record calls to both providers without their keys

  Scenario: OpenWeatherMap takes over when Weatherstack rejects the access key
    Given Weatherstack rejects the access key
    And OpenWeatherMap reports 30.4 degrees and wind 5.0 m/s
    When a client asks for the weather in singapore
    Then the client receives temperature 30 and wind speed 18

  Scenario: OpenWeatherMap takes over within the provider timeout when Weatherstack hangs
    Given Weatherstack hangs
    And OpenWeatherMap reports 30.4 degrees and wind 5.0 m/s
    When a client asks for the weather in singapore
    Then the client receives temperature 30 and wind speed 18
    And the client got an answer within 3 seconds

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

  Scenario: The last known weather is served as stale when every provider hangs
    Given Weatherstack reports 29 degrees and wind 20 km/h
    And a client fetched the weather in singapore 60 seconds ago
    And Weatherstack hangs
    And OpenWeatherMap hangs
    When a client asks for the weather in singapore
    Then the client receives temperature 29 and wind speed 20
    And the response is marked stale
    And the client got an answer within 5 seconds

  Scenario: A result obtained from OpenWeatherMap is the fallback during a later total outage
    Given Weatherstack is down
    And OpenWeatherMap reports 30.4 degrees and wind 5.0 m/s
    And a client fetched the weather in singapore 60 seconds ago
    And OpenWeatherMap is down
    When a client asks for the weather in singapore
    Then the client receives temperature 30 and wind speed 18
    And the response is marked stale

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

  Scenario: A stale result is replaced as soon as a provider recovers, even with both breakers open
    Given Weatherstack reports 29 degrees and wind 20 km/h
    And a client fetched the weather in singapore 60 seconds ago
    And Weatherstack is down
    And OpenWeatherMap is down
    When a client asks for the weather in singapore 3 times, 4 seconds apart
    Then the health endpoint reports weatherstack as OPEN
    And the health endpoint reports openweathermap as OPEN
    And the response is marked stale
    When Weatherstack reports 31 degrees and wind 12 km/h
    And 31 seconds pass
    And a client asks for the weather in singapore
    Then the client receives temperature 31 and wind speed 12
    And the response is not marked stale

  Scenario: Nothing to serve when every provider is down and nothing was cached
    Given Weatherstack is down
    And OpenWeatherMap is down
    When a client asks for the weather in singapore
    Then the client receives status 503
    And the health endpoint shows the cache for singapore as empty

  Scenario: Only Singapore is supported
    When a client asks for the weather in tokyo
    Then the client receives status 400
