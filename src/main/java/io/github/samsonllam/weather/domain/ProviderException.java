package io.github.samsonllam.weather.domain;

/** A provider could not deliver a usable observation: network failure, error payload, malformed body. */
public class ProviderException extends RuntimeException {

    public ProviderException(String providerName, String message) {
        this(providerName, message, null);
    }

    public ProviderException(String providerName, String message, Throwable cause) {
        super(providerName + ": " + message, cause);
    }
}
