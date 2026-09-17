package io.github.samsonllam.weather.domain;

import java.util.List;

/** Every provider in the failover chain failed; each individual failure is attached as a suppressed exception. */
public class AllProvidersFailedException extends ProviderException {

    public AllProvidersFailedException(String chainName, List<ProviderException> failures) {
        super(chainName, "all " + failures.size() + " providers failed");
        failures.forEach(this::addSuppressed);
    }
}
