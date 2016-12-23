package com.github.kristofa.brave;

import com.github.kristofa.brave.internal.Nullable;
import com.twitter.zipkin.gen.Annotation;
import com.twitter.zipkin.gen.BinaryAnnotation;
import com.twitter.zipkin.gen.Endpoint;
import com.twitter.zipkin.gen.Span;
import java.util.Random;
import zipkin.reporter.Reporter;

import static com.github.kristofa.brave.internal.DefaultSpanCodec.toZipkin;
import static com.github.kristofa.brave.internal.Util.checkNotNull;

/**
 * Used to submit application specific annotations.
 *
 * @author kristof
 */
public abstract class AnnotationSubmitter {

    /**
     * This interface is used to make the implementation to AnnotationSubmitter.currentTimeMicroseconds() contextual.
     * The clock is defined by the subclass's implementation of the `clock()` method.
     * A DefaultClock implementation is provided that simply returns `System.currentTimeMillis() * 1000`.
     */
    public interface Clock {
        /**
         * Epoch microseconds used for {@link zipkin.Span#timestamp} and {@link zipkin.Annotation#timestamp}.
         *
         * <p>This should use the most precise value possible. For example, {@code gettimeofday} or multiplying
         * {@link System#currentTimeMillis} by 1000.
         *
         * <p>See <a href="http://zipkin.io/pages/instrumenting.html">Instrumenting a service</a> for more.
         */
        long currentTimeMicroseconds();
    }

    public static AnnotationSubmitter create(SpanAndEndpoint spanAndEndpoint, Clock clock) {
        return new AnnotationSubmitterImpl(spanAndEndpoint, clock);
    }

    abstract SpanAndEndpoint spanAndEndpoint();
    abstract Random randomGenerator();
    abstract boolean traceId128Bit();

    /**
     * The implementation of Clock to use.
     *
     * See {@link com.github.kristofa.brave.AnnotationSubmitter.Clock}
     */
    abstract Clock clock();

    /**
     * Associates an event that explains latency with the current system time.
     *
     * @param value A short tag indicating the event, like "finagle.retry"
     */
    public void submitAnnotation(String value) {
        Span span = spanAndEndpoint().span();
        if (span != null) {
            Annotation annotation = Annotation.create(
                clock().currentTimeMicroseconds(),
                value,
                spanAndEndpoint().endpoint()
            );
            addAnnotation(span, annotation);
        }
    }

    /**
     * Associates an event that explains latency with a timestamp.
     *
     * <p/> This is an alternative to {@link #submitAnnotation(String)}, when
     * you have a timestamp more precise or accurate than {@link System#currentTimeMillis()}.
     *
     * @param value     A short tag indicating the event, like "finagle.retry"
     * @param timestamp microseconds from epoch
     */
    public void submitAnnotation(String value, long timestamp) {
        Span span = spanAndEndpoint().span();
        if (span != null) {
            Annotation annotation = Annotation.create(
                timestamp,
                value,
                spanAndEndpoint().endpoint()
            );
            addAnnotation(span, annotation);
        }
    }

    /** This adds an annotation that corresponds with {@link Span#getTimestamp()} */
    void submitStartAnnotation(String annotationName) {
        Span span = spanAndEndpoint().span();
        if (span != null) {
            Annotation annotation = Annotation.create(
                clock().currentTimeMicroseconds(),
                annotationName,
                spanAndEndpoint().endpoint()
            );
            synchronized (span) {
                span.setTimestamp(annotation.timestamp);
                span.addToAnnotations(annotation);
            }
        }
    }

    /**
     * This adds an annotation that corresponds with {@link Span#getDuration()}, and sends the span
     * for collection.
     *
     * @return true if a span was sent for collection.
     */
    boolean submitEndAnnotation(String annotationName, Reporter<zipkin.Span> reporter) {
        Span span = spanAndEndpoint().span();
        if (span == null) {
          return false;
        }

        long endTimestamp = clock().currentTimeMicroseconds();

        Annotation annotation = Annotation.create(
            endTimestamp,
            annotationName,
            spanAndEndpoint().endpoint()
        );
        synchronized (span) {
            span.addToAnnotations(annotation);
            Long startTimestamp = span.getTimestamp();
            if (startTimestamp != null) {
                span.setDuration(Math.max(1L, endTimestamp - startTimestamp));
            }
        }
        reporter.report(toZipkin(span));
        return true;
    }

    /**
     * Internal api for submitting an address. Until a naming function is added, this coerces null
     * {@code serviceName} to "unknown", as that's zipkin's convention.
     */
    void submitAddress(String key, Endpoint endpoint) {
        Span span = spanAndEndpoint().span();
        if (span != null) {
            if (endpoint.service_name == null) {
                endpoint = endpoint.toBuilder().serviceName("unknown").build();
            }
            BinaryAnnotation ba = BinaryAnnotation.address(key, endpoint);
            addBinaryAnnotation(span, ba);
        }
    }

    /**
     * Binary annotations are tags applied to a Span to give it context. For
     * example, a key "your_app.version" would let you lookup spans by version.
     *
     * @param key Name used to lookup spans, such as "your_app.version"
     * @param value String value, should not be <code>null</code>.
     */
    public void submitBinaryAnnotation(String key, String value) {
        Span span = spanAndEndpoint().span();
        if (span != null) {
            BinaryAnnotation ba = BinaryAnnotation.create(key, value, spanAndEndpoint().endpoint());
            addBinaryAnnotation(span, ba);
        }
    }

    /**
     * Submits a binary (key/value) annotation with int value.
     *
     * @param key Key, should not be blank.
     * @param value Integer value.
     */
    public void submitBinaryAnnotation(String key, int value) {
        // Zipkin v1 UI and query only support String annotations.
        submitBinaryAnnotation(key, String.valueOf(value));
    }

    private void addAnnotation(Span span, Annotation annotation) {
        synchronized (span) {
            span.addToAnnotations(annotation);
        }
    }

    private void addBinaryAnnotation(Span span, BinaryAnnotation ba) {
        synchronized (span) {
            span.addToBinary_annotations(ba);
        }
    }

    SpanId nextContext(@Nullable Span maybeParent) {
        long newSpanId = randomGenerator().nextLong();
        if (maybeParent == null) { // new trace
            return SpanId.builder()
                .spanId(newSpanId)
                .traceIdHigh(traceId128Bit() ? randomGenerator().nextLong() : 0L).build();
        } else if (maybeParent.context() != null) {
            return maybeParent.context().toBuilder()
                .parentId(maybeParent.getId())
                .spanId(newSpanId).build();
        }
        // If we got here, some implementation of state passed a deprecated span
        return SpanId.builder()
            .traceIdHigh(maybeParent.getTrace_id_high())
            .traceId(maybeParent.getTrace_id())
            .parentId(maybeParent.getId())
            .spanId(newSpanId).build();
    }

    AnnotationSubmitter() {
    }

    private static final class AnnotationSubmitterImpl extends AnnotationSubmitter {

        private final SpanAndEndpoint spanAndEndpoint;
        private final Clock clock;

        private AnnotationSubmitterImpl(SpanAndEndpoint spanAndEndpoint, Clock clock) {
            this.spanAndEndpoint = checkNotNull(spanAndEndpoint, "Null spanAndEndpoint");
            this.clock = clock;
        }

        @Override
        SpanAndEndpoint spanAndEndpoint() {
            return spanAndEndpoint;
        }

        @Override Random randomGenerator() {
            throw new UnsupportedOperationException();
        }

        @Override boolean traceId128Bit() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected Clock clock() {
            return clock;
        }

    }

    /** Offset-based clock: Uses a single point of reference and offsets to create timestamps. */
    static final class DefaultClock implements Clock {
        static final Clock INSTANCE = new DefaultClock();
        // epochMicros is derived by this
        final long createTimestamp;
        final long createTick;

        DefaultClock() {
            createTimestamp = System.currentTimeMillis() * 1000;
            createTick = System.nanoTime();
        }

        /** gets a timestamp based on this the create tick. */
        @Override
        public long currentTimeMicroseconds() {
            return ((System.nanoTime() - createTick) / 1000) + createTimestamp;
        }
    }
}
