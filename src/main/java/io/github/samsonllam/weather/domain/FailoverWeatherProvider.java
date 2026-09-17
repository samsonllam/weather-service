package io.github.samsonllam.weather.domain;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tries each provider in order and returns the first successful observation.
 *
 * <p>Any runtime exception from a provider, not only {@link ProviderException}, counts as a failure:
 * a bug in one adapter must not take the whole endpoint down. Unexpected exceptions are logged with
 * their stack trace so they remain visible.
 */
public final class FailoverWeatherProvider implements WeatherProvider {

    private static final Logger log = LoggerFactory.getLogger(FailoverWeatherProvider.class);

    private final List<WeatherProvider> providers;

    /** @param providers providers in priority order; the first one is the primary */
    public FailoverWeatherProvider(List<WeatherProvider> providers) {
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("at least one provider is required");
        }
        this.providers = List.copyOf(providers);
    }

    @Override
    public String name() {
        return "failover";
    }

    @Override
    public Weather currentWeather(City city) {
        List<ProviderException> failures = new ArrayList<>();
        for (WeatherProvider provider : providers) {
            try {
                return provider.currentWeather(city);
            } catch (ProviderException e) {
                log.warn("Provider {} failed for {}: {}", provider.name(), city, e.getMessage());
                failures.add(e);
            } catch (RuntimeException e) {
                log.error("Provider {} threw an unexpected exception for {}", provider.name(), city, e);
                failures.add(new ProviderException(provider.name(), "unexpected " + e.getClass().getSimpleName(), e));
            }
        }
        throw new AllProvidersFailedException(failures);
    }
}
