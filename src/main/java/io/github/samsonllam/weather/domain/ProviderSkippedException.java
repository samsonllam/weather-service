package io.github.samsonllam.weather.domain;

/**
 * A provider was not called at all because it is known to be failing (its circuit breaker is open).
 * Distinguished from other failures so that it can be logged quietly: it is expected during an outage.
 */
public class ProviderSkippedException extends ProviderException {

    public ProviderSkippedException(String providerName, String message, Throwable cause) {
        super(providerName, message, cause);
    }
}
