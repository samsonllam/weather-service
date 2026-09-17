package io.github.samsonllam.weather.provider;

import io.github.samsonllam.weather.domain.ProviderException;
import java.util.function.Supplier;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Translates {@link RestClientException}s into {@link ProviderException}s.
 *
 * <p>The messages are rebuilt deliberately: Spring's own messages echo the request URL, which
 * carries the provider API key, and they would otherwise end up in the logs.
 */
public final class ProviderHttp {

    private ProviderHttp() {
    }

    public static <T> T call(String providerName, Supplier<T> request) {
        T body;
        try {
            body = request.get();
        } catch (RestClientResponseException e) {
            throw new ProviderException(providerName, "HTTP " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new ProviderException(providerName, "I/O failure: " + rootCause(e).getClass().getSimpleName(), e);
        } catch (RestClientException e) {
            throw new ProviderException(providerName, "unreadable response: " + e.getClass().getSimpleName(), e);
        }
        if (body == null) {
            throw new ProviderException(providerName, "empty response body");
        }
        return body;
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
