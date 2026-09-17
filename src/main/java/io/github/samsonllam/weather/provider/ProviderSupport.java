package io.github.samsonllam.weather.provider;

import io.github.samsonllam.weather.domain.ProviderException;
import io.github.samsonllam.weather.domain.Weather;
import java.util.function.Supplier;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Shared plumbing for the provider adapters: HTTP failure translation and value validation. */
public final class ProviderSupport {

    private ProviderSupport() {
    }

    /**
     * Runs an HTTP call and maps every {@link RestClientException} to a {@link ProviderException}.
     *
     * <p>Messages are rebuilt rather than copied from the HTTP client, so that log lines stay short,
     * provider-specific and independent of framework behaviour. Spring itself already strips the
     * query string, and with it the API key, from its own exception messages; this is defence in depth.
     */
    public static <T> T call(String providerName, Supplier<T> request) {
        T body;
        try {
            body = request.get();
        } catch (RestClientResponseException e) {
            throw new ProviderException(providerName, "HTTP " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new ProviderException(providerName, "I/O failure: " + rootCause(e).getClass().getSimpleName(), e);
        } catch (RestClientException e) {
            throw new ProviderException(providerName, "unreadable response: " + rootCause(e).getClass().getSimpleName(), e);
        }
        if (body == null) {
            throw new ProviderException(providerName, "empty response body");
        }
        return body;
    }

    /** Builds the domain value, turning an implausible reading into a provider failure rather than a cached fact. */
    public static Weather weather(String providerName, double temperatureCelsius, double windSpeedKph) {
        try {
            return new Weather(temperatureCelsius, windSpeedKph);
        } catch (IllegalArgumentException e) {
            throw new ProviderException(providerName, e.getMessage());
        }
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
