package brave;

import brave.Tracer.SpanInScope;
import brave.propagation.B3Propagation;
import brave.propagation.ExtraFieldPropagation;
import brave.propagation.Propagation;
import brave.propagation.SamplingFlags;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.propagation.TraceIdContext;
import brave.sampler.Sampler;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import zipkin2.Endpoint;
import zipkin2.reporter.Reporter;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

public class TracerTest {
  List<zipkin2.Span> spans = new ArrayList<>();
  Propagation.Factory propagationFactory = B3Propagation.FACTORY;
  Tracer tracer = Tracing.newBuilder()
      .spanReporter(new Reporter<zipkin2.Span>() {
        @Override public void report(zipkin2.Span span) {
          spans.add(span);
        }

        @Override public String toString() {
          return "MyReporter{}";
        }
      })
      .propagationFactory(new Propagation.Factory() {
        @Override public <K> Propagation<K> create(Propagation.KeyFactory<K> keyFactory) {
          return propagationFactory.create(keyFactory);
        }

        @Override public boolean supportsJoin() {
          return propagationFactory.supportsJoin();
        }

        @Override public boolean requires128BitTraceId() {
          return propagationFactory.requires128BitTraceId();
        }

        @Override public TraceContext decorate(TraceContext context) {
          return propagationFactory.decorate(context);
        }
      })
      .currentTraceContext(ThreadLocalCurrentTraceContext.create())
      .localServiceName("my-service")
      .build().tracer();

  @After public void close() {
    Tracing.current().close();
  }

  @Test public void sampler() {
    Sampler sampler = new Sampler() {
      @Override public boolean isSampled(long traceId) {
        return false;
      }
    };

    tracer = Tracing.newBuilder().sampler(sampler).build().tracer();

    assertThat(tracer.sampler)
        .isSameAs(sampler);
  }

  @Test public void withSampler() {
    Sampler sampler = new Sampler() {
      @Override public boolean isSampled(long traceId) {
        return false;
      }
    };

    tracer = tracer.withSampler(sampler);

    assertThat(tracer.sampler)
        .isSameAs(sampler);
  }

  @Test public void localServiceName() {
    tracer = Tracing.newBuilder().localServiceName("my-foo").build().tracer();

    assertThat(tracer).extracting("pendingSpans.localEndpoint.serviceName")
        .containsExactly("my-foo");
  }

  @Test public void localServiceName_defaultIsUnknown() {
    tracer = Tracing.newBuilder().build().tracer();

    assertThat(tracer).extracting("pendingSpans.localEndpoint.serviceName")
        .containsExactly("unknown");
  }

  @Test public void localServiceName_ignoredWhenGivenLocalEndpoint() {
    Endpoint endpoint = Endpoint.newBuilder().ip("1.2.3.4").serviceName("my-bar").build();
    tracer = Tracing.newBuilder().localServiceName("my-foo").endpoint(endpoint).build().tracer();

    assertThat(tracer).extracting("pendingSpans.localEndpoint")
        .allSatisfy(e -> assertThat(e).isEqualTo(endpoint));
  }

  @Test public void newTrace_isRootSpan() {
    assertThat(tracer.newTrace())
        .satisfies(s -> assertThat(s.context().parentId()).isNull())
        .isInstanceOf(RealSpan.class);
  }

  @Test public void newTrace_traceId128Bit() {
    tracer = Tracing.newBuilder().traceId128Bit(true).build().tracer();

    assertThat(tracer.newTrace().context().traceIdHigh())
        .isNotZero();
  }

  @Test public void newTrace_unsampled_tracer() {
    tracer = tracer.withSampler(Sampler.NEVER_SAMPLE);

    assertThat(tracer.newTrace())
        .isInstanceOf(NoopSpan.class);
  }

  /** When we join a sampled request, we are sharing the same trace identifiers. */
  @Test public void join_setsShared() {
    TraceContext fromIncomingRequest = tracer.newTrace().context();

    assertThat(tracer.joinSpan(fromIncomingRequest).context())
        .isEqualTo(fromIncomingRequest.toBuilder().shared(true).build());
  }

  /**
   * Data from loopback requests should be partitioned into two spans: one for the client and the
   * other for the server.
   */
  @Test public void join_sharedDataIsSeparate() {
    Span clientSide = tracer.newTrace().kind(Span.Kind.CLIENT).start(1L);
    Span serverSide = tracer.joinSpan(clientSide.context()).kind(Span.Kind.SERVER).start(2L);
    serverSide.finish(3L);
    clientSide.finish(4L);

    // Ensure they use the same span ID (sanity check)
    String spanId = spans.get(0).id();
    assertThat(spans).extracting(zipkin2.Span::id)
        .containsExactly(spanId, spanId);

    // Ensure the important parts are separated correctly
    assertThat(spans).extracting(
        zipkin2.Span::kind, zipkin2.Span::shared, zipkin2.Span::timestamp, zipkin2.Span::duration
    ).containsExactly(
        tuple(zipkin2.Span.Kind.SERVER, true, 2L, 1L),
        tuple(zipkin2.Span.Kind.CLIENT, null, 1L, 3L)
    );
  }

  @Test public void join_createsChildWhenUnsupported() {
    tracer = Tracing.newBuilder().supportsJoin(false).spanReporter(spans::add).build().tracer();

    TraceContext fromIncomingRequest = tracer.newTrace().context();

    TraceContext shouldBeChild = tracer.joinSpan(fromIncomingRequest).context();
    assertThat(shouldBeChild.shared())
        .isFalse();
    assertThat(shouldBeChild.parentId())
        .isEqualTo(fromIncomingRequest.spanId());
  }

  @Test public void finish_doesntCrashOnBadReporter() {
    tracer = Tracing.newBuilder().spanReporter(span -> {
      throw new RuntimeException();
    }).build().tracer();

    tracer.newTrace().start().finish();
  }

  @Test public void join_createsChildWhenUnsupportedByPropagation() {
    tracer = Tracing.newBuilder()
        .propagationFactory(new Propagation.Factory() {
          @Override public <K> Propagation<K> create(Propagation.KeyFactory<K> keyFactory) {
            return B3Propagation.FACTORY.create(keyFactory);
          }
        })
        .spanReporter(spans::add).build().tracer();

    TraceContext fromIncomingRequest = tracer.newTrace().context();

    TraceContext shouldBeChild = tracer.joinSpan(fromIncomingRequest).context();
    assertThat(shouldBeChild.shared())
        .isFalse();
    assertThat(shouldBeChild.parentId())
        .isEqualTo(fromIncomingRequest.spanId());
  }

  @Test public void join_noop() {
    TraceContext fromIncomingRequest = tracer.newTrace().context();

    tracer.noop.set(true);

    assertThat(tracer.joinSpan(fromIncomingRequest))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void join_ensuresSampling() {
    TraceContext notYetSampled =
        tracer.newTrace().context().toBuilder().sampled(null).build();

    assertThat(tracer.joinSpan(notYetSampled).context())
        .isEqualTo(notYetSampled.toBuilder().sampled(true).build());
  }

  @Test public void newChild_ensuresSampling() {
    TraceContext notYetSampled =
        tracer.newTrace().context().toBuilder().sampled(null).build();

    assertThat(tracer.newChild(notYetSampled).context().sampled())
        .isTrue();
  }

  @Test public void nextSpan_ensuresSampling_whenCreatingNewChild() {
    TraceContext notYetSampled =
        tracer.newTrace().context().toBuilder().sampled(null).build();

    TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.create(notYetSampled);
    assertThat(tracer.nextSpan(extracted).context().sampled())
        .isTrue();
  }

  @Test public void toSpan() {
    TraceContext context = tracer.newTrace().context();

    assertThat(tracer.toSpan(context))
        .isInstanceOf(RealSpan.class)
        .extracting(Span::context)
        .containsExactly(context);
  }

  @Test public void toSpan_noop() {
    TraceContext context = tracer.newTrace().context();

    tracer.noop.set(true);

    assertThat(tracer.toSpan(context))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void toSpan_unsampledIsNoop() {
    TraceContext unsampled =
        tracer.newTrace().context().toBuilder().sampled(false).build();

    assertThat(tracer.toSpan(unsampled))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void newChild() {
    TraceContext parent = tracer.newTrace().context();

    assertThat(tracer.newChild(parent))
        .satisfies(c -> {
          assertThat(c.context().traceIdString()).isEqualTo(parent.traceIdString());
          assertThat(c.context().parentId()).isEqualTo(parent.spanId());
        })
        .isInstanceOf(RealSpan.class);
  }

  /** A child span is not sharing a span ID with its parent by definition */
  @Test public void newChild_isntShared() {
    TraceContext parent = tracer.newTrace().context();

    assertThat(tracer.newChild(parent).context().shared())
        .isFalse();
  }

  @Test public void newChild_noop() {
    TraceContext parent = tracer.newTrace().context();

    tracer.noop.set(true);

    assertThat(tracer.newChild(parent))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void newChild_unsampledIsNoop() {
    TraceContext unsampled =
        tracer.newTrace().context().toBuilder().sampled(false).build();

    assertThat(tracer.newChild(unsampled))
        .isInstanceOf(NoopSpan.class);
  }

  @Test public void currentSpanCustomizer_defaultsToNoop() {
    assertThat(tracer.currentSpanCustomizer())
        .isSameAs(NoopSpanCustomizer.INSTANCE);
  }

  @Test public void currentSpanCustomizer_noop_when_unsampled() {
    ScopedSpan parent = tracer.withSampler(Sampler.NEVER_SAMPLE).startScopedSpan("parent");
    try {
      assertThat(tracer.currentSpanCustomizer())
          .isSameAs(NoopSpanCustomizer.INSTANCE);
    } finally {
      parent.finish();
    }
  }

  @Test public void currentSpanCustomizer_real_when_sampled() {
    ScopedSpan parent = tracer.startScopedSpan("parent");

    try {
      assertThat(tracer.currentSpanCustomizer())
          .isInstanceOf(RealSpanCustomizer.class);
    } finally {
      parent.finish();
    }
  }

  @Test public void currentSpan_defaultsToNull() {
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test public void nextSpan_defaultsToMakeNewTrace() {
    assertThat(tracer.nextSpan().context().parentId()).isNull();
  }

  @Test public void nextSpan_extractedNothing_makesChildOfCurrent() {
    Span parent = tracer.newTrace();

    try (SpanInScope ws = tracer.withSpanInScope(parent)) {
      Span nextSpan = tracer.nextSpan(TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY));
      assertThat(nextSpan.context().parentId())
          .isEqualTo(parent.context().spanId());
    }
  }

  @Test public void nextSpan_extractedNothing_defaultsToMakeNewTrace() {
    Span nextSpan = tracer.nextSpan(TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY));

    assertThat(nextSpan.context().parentId())
        .isNull();
  }

  @Test public void nextSpan_makesChildOfCurrent() {
    Span parent = tracer.newTrace();

    try (SpanInScope ws = tracer.withSpanInScope(parent)) {
      assertThat(tracer.nextSpan().context().parentId())
          .isEqualTo(parent.context().spanId());
    }
  }

  @Test public void nextSpan_extractedExtra_newTrace() {
    TraceContextOrSamplingFlags extracted =
        TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY).toBuilder().addExtra(1L).build();

    assertThat(tracer.nextSpan(extracted).context().extra())
        .containsExactly(1L);
  }

  @Test public void nextSpan_extractedExtra_childOfCurrent() {
    Span parent = tracer.newTrace();

    TraceContextOrSamplingFlags extracted =
        TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY).toBuilder().addExtra(1L).build();

    try (SpanInScope ws = tracer.withSpanInScope(parent)) {
      assertThat(tracer.nextSpan(extracted).context().extra())
          .containsExactly(1L);
    }
  }

  @Test public void nextSpan_extractedExtra_appendsToChildOfCurrent() {
    // current parent already has extra stuff
    Span parent = tracer.toSpan(tracer.newTrace().context().toBuilder().extra(asList(1L)).build());

    TraceContextOrSamplingFlags extracted =
        TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY).toBuilder().addExtra(2L).build();

    try (SpanInScope ws = tracer.withSpanInScope(parent)) {
      assertThat(tracer.nextSpan(extracted).context().extra())
          .containsExactly(1L, 2L);
    }
  }

  @Test public void nextSpan_extractedTraceId() {
    TraceIdContext traceIdContext = TraceIdContext.newBuilder().traceId(1L).build();
    TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.create(traceIdContext);

    assertThat(tracer.nextSpan(extracted).context().traceId())
        .isEqualTo(1L);
  }

  @Test public void nextSpan_extractedTraceId_extra() {
    TraceIdContext traceIdContext = TraceIdContext.newBuilder().traceId(1L).build();
    TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.create(traceIdContext)
        .toBuilder().addExtra(1L).build();

    assertThat(tracer.nextSpan(extracted).context().extra())
        .containsExactly(1L);
  }

  @Test public void nextSpan_extractedTraceContext() {
    TraceContext traceContext = TraceContext.newBuilder().traceId(1L).spanId(2L).build();
    TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.create(traceContext);

    assertThat(tracer.nextSpan(extracted).context())
        .extracting(TraceContext::traceId, TraceContext::parentId)
        .containsExactly(1L, 2L);
  }

  @Test public void nextSpan_extractedTraceContext_extra() {
    TraceContext traceContext = TraceContext.newBuilder().traceId(1L).spanId(2L).build();
    TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.create(traceContext)
        .toBuilder().addExtra(1L).build();

    assertThat(tracer.nextSpan(extracted).context().extra())
        .contains(1L);
  }

  @Test public void startScopedSpan_isInScope() {
    RealScopedSpan current = (RealScopedSpan) tracer.startScopedSpan("foo");

    try {
      assertThat(tracer.currentSpan().context())
          .isEqualTo(current.context);
      assertThat(tracer.currentSpanCustomizer())
          .isNotEqualTo(NoopSpanCustomizer.INSTANCE);
    } finally {
      current.finish();
    }

    // context was cleared
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test public void startScopedSpan_noopIsInScope() {
    tracer = tracer.withSampler(Sampler.NEVER_SAMPLE);
    NoopScopedSpan current = (NoopScopedSpan) tracer.startScopedSpan("foo");

    try {
      assertThat(tracer.currentSpan().context())
          .isEqualTo(current.context);
      assertThat(tracer.currentSpanCustomizer())
          .isSameAs(NoopSpanCustomizer.INSTANCE);
    } finally {
      current.finish();
    }

    // context was cleared
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test public void withSpanInScope() {
    Span current = tracer.newTrace();

    try (SpanInScope ws = tracer.withSpanInScope(current)) {
      assertThat(tracer.currentSpan())
          .isEqualTo(current);
      assertThat(tracer.currentSpanCustomizer())
          .isNotEqualTo(current)
          .isNotEqualTo(NoopSpanCustomizer.INSTANCE);
    }

    // context was cleared
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test public void withNoopSpanInScope() {
    Span current = tracer.withSampler(Sampler.NEVER_SAMPLE).nextSpan();

    try (SpanInScope ws = tracer.withSpanInScope(current)) {
      assertThat(tracer.currentSpan())
          .isEqualTo(current);
      assertThat(tracer.currentSpanCustomizer())
          .isNotEqualTo(current)
          .isEqualTo(NoopSpanCustomizer.INSTANCE);
    }

    // context was cleared
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test public void toString_withSpanInScope() {
    TraceContext context = TraceContext.newBuilder().traceId(1L).spanId(10L).build();
    try (SpanInScope ws = tracer.withSpanInScope(tracer.toSpan(context))) {
      assertThat(tracer.toString()).hasToString(
          "Tracer{currentSpan=0000000000000001/000000000000000a, reporter=MyReporter{}}"
      );
    }
  }

  @Test public void toString_withSpanInFlight() {
    TraceContext context = TraceContext.newBuilder().traceId(1L).spanId(10L).sampled(true).build();
    Span span = tracer.toSpan(context);
    span.start(1L); // didn't set anything else! this is to help ensure no NPE

    assertThat(tracer).hasToString(
        "Tracer{inFlight=[{\"traceId\":\"0000000000000001\",\"id\":\"000000000000000a\",\"timestamp\":1}], reporter=MyReporter{}}"
    );

    span.finish();

    assertThat(tracer).hasToString(
        "Tracer{reporter=MyReporter{}}"
    );
  }

  @Test public void toString_whenNoop() {
    Tracing.current().setNoop(true);

    assertThat(tracer).hasToString(
        "Tracer{noop=true, reporter=MyReporter{}}"
    );
  }

  @Test public void withSpanInScope_nested() {
    Span parent = tracer.newTrace();

    try (SpanInScope wsParent = tracer.withSpanInScope(parent)) {

      Span child = tracer.newChild(parent.context());
      try (SpanInScope wsChild = tracer.withSpanInScope(child)) {
        assertThat(tracer.currentSpan())
            .isEqualTo(child);
      }

      // old parent reverted
      assertThat(tracer.currentSpan())
          .isEqualTo(parent);
    }
  }

  @Test public void withSpanInScope_clear() {
    Span parent = tracer.newTrace();

    try (SpanInScope wsParent = tracer.withSpanInScope(parent)) {
      try (SpanInScope clearScope = tracer.withSpanInScope(null)) {
        assertThat(tracer.currentSpan())
            .isNull();
        assertThat(tracer.currentSpanCustomizer())
            .isEqualTo(NoopSpanCustomizer.INSTANCE);
      }

      // old parent reverted
      assertThat(tracer.currentSpan())
          .isEqualTo(parent);
    }
  }

  @Test public void join_getsExtraFromPropagationFactory() {
    propagationFactory = ExtraFieldPropagation.newFactory(B3Propagation.FACTORY, "service");

    TraceContext context = tracer.nextSpan().context();
    ExtraFieldPropagation.set(context, "service", "napkin");

    TraceContext joined = tracer.joinSpan(context).context();

    assertThat(ExtraFieldPropagation.get(joined, "service")).isEqualTo("napkin");
  }

  @Test public void nextSpan_getsExtraFromPropagationFactory() {
    propagationFactory = ExtraFieldPropagation.newFactory(B3Propagation.FACTORY, "service");

    Span parent = tracer.nextSpan();
    ExtraFieldPropagation.set(parent.context(), "service", "napkin");

    TraceContext nextSpan;
    try (SpanInScope scope = tracer.withSpanInScope(parent)) {
      nextSpan = tracer.nextSpan().context();
    }

    assertThat(ExtraFieldPropagation.get(nextSpan, "service")).isEqualTo("napkin");
  }

  @Test public void newChild_getsExtraFromPropagationFactory() {
    propagationFactory = ExtraFieldPropagation.newFactory(B3Propagation.FACTORY, "service");

    TraceContext context = tracer.nextSpan().context();
    ExtraFieldPropagation.set(context, "service", "napkin");

    TraceContext newChild = tracer.newChild(context).context();

    assertThat(ExtraFieldPropagation.get(newChild, "service")).isEqualTo("napkin");
  }

  @Test public void startScopedSpanWithParent_getsExtraFromPropagationFactory() {
    propagationFactory = ExtraFieldPropagation.newFactory(B3Propagation.FACTORY, "service");

    TraceContext context = tracer.nextSpan().context();
    ExtraFieldPropagation.set(context, "service", "napkin");

    ScopedSpan scoped = tracer.startScopedSpanWithParent("foo", context);
    scoped.finish();

    assertThat(ExtraFieldPropagation.get(scoped.context(), "service")).isEqualTo("napkin");
  }

  @Test public void startScopedSpan_getsExtraFromPropagationFactory() {
    propagationFactory = ExtraFieldPropagation.newFactory(B3Propagation.FACTORY, "service");

    Span parent = tracer.nextSpan();
    ExtraFieldPropagation.set(parent.context(), "service", "napkin");

    ScopedSpan scoped;
    try (SpanInScope scope = tracer.withSpanInScope(parent)) {
      scoped = tracer.startScopedSpan("foo");
      scoped.finish();
    }

    assertThat(ExtraFieldPropagation.get(scoped.context(), "service")).isEqualTo("napkin");
  }
}
