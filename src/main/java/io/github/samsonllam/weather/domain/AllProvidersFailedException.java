package io.github.samsonllam.weather.domain;

import java.util.List;

/** Every provider in the failover chain failed; each individual failure is attached as a suppressed exception. */
public class AllProvidersFailedException extends ProviderException {

    public AllProvidersFailedException(List<ProviderException> failures) {
        super("failover", "all " + failures.size() + " providers failed");
        failures.forEach(this::addSuppressed);
    }
}
