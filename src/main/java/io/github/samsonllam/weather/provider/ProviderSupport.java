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
     * Runs an HTTP call and maps every {@link RestClientException} to a {@link ProviderException}
     * whose message is built here from a status code or an exception class name, never from
     * upstream text.
     *
     * <p>Exceptions carrying response content (HTTP error statuses, decoding failures) are not kept
     * as the cause: Spring quotes the response body in their messages, and a provider that echoes
     * the request could put the API key into a stack trace. Transport failures carry no body and
     * are kept, because their root cause is the useful diagnostic.
     */
    public static <T> T call(String providerName, Supplier<T> request) {
        T body;
        try {
            body = request.get();
        } catch (RestClientResponseException e) {
            throw new ProviderException(providerName, "HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw new ProviderException(providerName, "I/O failure: " + rootCause(e).getClass().getSimpleName(), e);
        } catch (RestClientException e) {
            throw new ProviderException(providerName, "unreadable response: " + rootCause(e).getClass().getSimpleName());
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
