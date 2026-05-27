package io.github.ulviar.procwright.comparison;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPLoggerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * No-op SLF4J provider for the comparison harness.
 *
 * <p>The comparison module depends on several external process libraries that optionally log through SLF4J. Procwright does
 * not need their logs for deterministic comparison checks, and adding a normal logger would make the research harness
 * noisier than the core runtime under test.
 */
public final class Slf4jNoOpServiceProvider implements SLF4JServiceProvider {

    private static final String REQUESTED_API_VERSION = "2.0.99";

    private final ILoggerFactory loggerFactory = new NOPLoggerFactory();
    private final IMarkerFactory markerFactory = new BasicMarkerFactory();
    private final MDCAdapter mdcAdapter = new NOPMDCAdapter();

    /**
     * Creates the no-op provider for SLF4J service loading.
     */
    public Slf4jNoOpServiceProvider() {}

    @Override
    public void initialize() {
        // No state to initialize.
    }

    @Override
    public ILoggerFactory getLoggerFactory() {
        return loggerFactory;
    }

    @Override
    public IMarkerFactory getMarkerFactory() {
        return markerFactory;
    }

    @Override
    public MDCAdapter getMDCAdapter() {
        return mdcAdapter;
    }

    @Override
    public String getRequestedApiVersion() {
        return REQUESTED_API_VERSION;
    }
}
