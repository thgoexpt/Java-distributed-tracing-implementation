package com.github.kristofa.brave;

import com.twitter.zipkin.gen.Endpoint;
import com.twitter.zipkin.gen.Span;

/**
 * {@link ServerAndClientSpanState} implementation.
 * 
 * @author kristof
 */
class ServerAndClientSpanStateImpl implements ServerAndClientSpanState {

    private final static ThreadLocal<Boolean> traceCurrentRequest = new ThreadLocal<Boolean>();
    private final static ThreadLocal<Span> currentServerSpan = new ThreadLocal<Span>();
    private final static ThreadLocal<Span> currentClientSpan = new ThreadLocal<Span>();

    private Endpoint endPoint;

    /**
     * {@inheritDoc}
     */
    @Override
    public Span getCurrentServerSpan() {
        return currentServerSpan.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCurrentServerSpan(final Span span) {
        currentServerSpan.set(span);

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean shouldTrace() {
        final Boolean value = traceCurrentRequest.get();
        if (value == null) {
            return true;
        }
        return value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setTracing(final boolean shouldTrace) {
        traceCurrentRequest.set(shouldTrace);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Endpoint getEndPoint() {
        return endPoint;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setEndPoint(final Endpoint endPoint) {
        this.endPoint = endPoint;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Span getCurrentClientSpan() {
        return currentClientSpan.get();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setCurrentClientSpan(final Span span) {
        currentClientSpan.set(span);
    }

}
