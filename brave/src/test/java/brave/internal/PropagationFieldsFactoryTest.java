package brave.internal;

import brave.ScopedSpan;
import brave.Tracing;
import brave.propagation.B3Propagation;
import brave.propagation.CurrentTraceContext;
import brave.propagation.Propagation;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import java.util.List;
import org.junit.Test;

import static brave.internal.TraceContexts.contextWithExtra;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public abstract class PropagationFieldsFactoryTest {
  static final String FIELD1 = "foo";
  static final String FIELD2 = "bar";

  PropagationFieldsFactory factory = newFactory();

  abstract PropagationFieldsFactory newFactory();

  Propagation.Factory propagationFactory = new Propagation.Factory() {
    @Override public <K> Propagation<K> create(Propagation.KeyFactory<K> keyFactory) {
      return B3Propagation.FACTORY.create(keyFactory);
    }

    @Override public TraceContext decorate(TraceContext context) {
      return factory.decorate(context);
    }
  };

  TraceContext context = propagationFactory.decorate(TraceContext.newBuilder()
      .traceId(1L)
      .spanId(2L)
      .sampled(true)
      .build());

  @Test public void contextsAreIndependent() {
    try (Tracing tracing = Tracing.newBuilder().propagationFactory(propagationFactory).build()) {

      TraceContext context1 = tracing.tracer().nextSpan().context();
      PropagationFields.put(context1, FIELD1, "1", factory.type());
      TraceContext context2 = tracing.tracer().newChild(context1).context();

      // Instances are not the same
      assertThat(context1.findExtra(factory.type()))
          .isNotSameAs(context2.findExtra(factory.type()));

      // But have the same values
      assertThat(context1.<PropagationFields>findExtra(factory.type()).toMap())
          .isEqualTo(context2.<PropagationFields>findExtra(factory.type()).toMap());
      assertThat(PropagationFields.get(context1, FIELD1, factory.type()))
          .isEqualTo(PropagationFields.get(context2, FIELD1, factory.type()))
          .isEqualTo("1");

      PropagationFields.put(context1, FIELD1, "2", factory.type());
      PropagationFields.put(context2, FIELD1, "3", factory.type());

      // Yet downstream changes don't affect eachother
      assertThat(PropagationFields.get(context1, FIELD1, factory.type()))
          .isEqualTo("2");
      assertThat(PropagationFields.get(context2, FIELD1, factory.type()))
          .isEqualTo("3");
    }
  }

  @Test public void contextIsntBrokenWithSmallChanges() {
    try (Tracing tracing = Tracing.newBuilder().propagationFactory(propagationFactory).build()) {

      TraceContext context1 = tracing.tracer().nextSpan().context();
      PropagationFields.put(context1, FIELD1, "1", factory.type());

      TraceContext context2 =
          tracing.tracer().toSpan(context1.toBuilder().sampled(false).build()).context();
      PropagationFields fields1 = (PropagationFields) context1.extra().get(0);
      PropagationFields fields2 = (PropagationFields) context2.extra().get(0);

      // we have the same span ID, so we should couple our extra fields
      assertThat(fields1).isSameAs(fields2);

      // we no longer have the same span ID, so we should decouple our extra fields
      TraceContext context3 =
          tracing.tracer().toSpan(context1.toBuilder().spanId(1L).build()).context();
      PropagationFields fields3 = (PropagationFields) context3.extra().get(0);

      // we have different instances of extra
      assertThat(fields1).isNotSameAs(fields3);

      // however, the values inside are the same until a write occurs
      assertThat(fields1.toMap()).isEqualTo(fields3.toMap());

      // inside the span, the same change is present, but the other span has the old values
      PropagationFields.put(context1, FIELD1, "2", factory.type());
      assertThat(fields1).isEqualToComparingFieldByField(fields2);
      assertThat(fields3.get(FIELD1)).isEqualTo("1");
    }
  }

  /**
   * This scenario is possible, albeit rare. {@code tracer.nextSpan(extracted} } is called when
   * there is an implicit parent. For example, you have a trace in progress when extracting trace
   * state from an incoming message. Another example is where there is a span in scope due to a leak
   * such as from using {@link CurrentTraceContext.Default#inheritable()}.
   *
   * <p>When we are only extracting extra fields, the state should merge as opposed to creating
   * duplicate copies of {@link PropagationFields}.
   */
  @Test public void nextSpanMergesExtraWithImplicitParent_hasFields() {
    try (Tracing tracing = Tracing.newBuilder().propagationFactory(propagationFactory).build()) {
      ScopedSpan parent = tracing.tracer().startScopedSpan("parent");
      try {
        PropagationFields.put(parent.context(), FIELD1, "1", factory.type());

        PropagationFields extractedPropagationFields = factory.create();
        extractedPropagationFields.put(FIELD1, "2"); // extracted should win!
        extractedPropagationFields.put(FIELD2, "a");

        TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.newBuilder()
            .samplingFlags(SamplingFlags.EMPTY)
            .addExtra(extractedPropagationFields)
            .build();

        TraceContext context1 = tracing.tracer().nextSpan(extracted).context();

        assertThat(context1.extra()) // merged
            .hasSize(1);
        PropagationFields fields = ((PropagationFields) context1.extra().get(0));
        assertThat(fields.toMap()).containsExactly(
            entry(FIELD1, "2"),
            entry(FIELD2, "a")
        );
        assertThat(fields).extracting("traceId", "spanId")
            .containsExactly(context1.traceId(), context1.spanId());
      } finally {
        parent.finish();
      }
    }
  }

  @Test public void decorate_empty() {
    assertThat(factory.decorate(contextWithExtra(context, asList(1L))).extra())
        .containsExactly(1L, factory.create());
    assertThat(factory.decorate(contextWithExtra(context, asList(1L, 2L))).extra())
        .containsExactly(1L, 2L, factory.create());
    assertThat(factory.decorate(contextWithExtra(context, asList(factory.create()))).extra())
        .containsExactly(factory.create());
    assertThat(factory.decorate(contextWithExtra(context, asList(factory.create(), 1L))).extra())
        .containsExactly(factory.create(), 1L);
    assertThat(factory.decorate(contextWithExtra(context, asList(1L, factory.create()))).extra())
        .containsExactly(1L, factory.create());

    PropagationFields claimedBySelf = factory.create();
    claimedBySelf.tryToClaim(context.traceId(), context.spanId());
    assertThat(factory.decorate(contextWithExtra(context, asList(claimedBySelf))).extra())
        .containsExactly(factory.create());
    assertThat(factory.decorate(contextWithExtra(context, asList(claimedBySelf, 1L))).extra())
        .containsExactly(factory.create(), 1L);
    assertThat(factory.decorate(contextWithExtra(context, asList(1L, claimedBySelf))).extra())
        .containsExactly(1L, factory.create());

    PropagationFields claimedByOther = factory.create();
    claimedBySelf.tryToClaim(99L, 99L);
    assertThat(factory.decorate(contextWithExtra(context, asList(claimedByOther))).extra())
        .containsExactly(factory.create());
    assertThat(factory.decorate(contextWithExtra(context, asList(claimedByOther, 1L))).extra())
        .containsExactly(factory.create(), 1L);
    assertThat(factory.decorate(contextWithExtra(context, asList(1L, claimedByOther))).extra())
        .containsExactly(1L, factory.create());
  }

  @Test public void nextSpanExtraWithImplicitParent_butNoImplicitExtraFields() {
    try (Tracing tracing = Tracing.newBuilder().propagationFactory(propagationFactory).build()) {

      ScopedSpan parent = tracing.tracer().startScopedSpan("parent");
      try {
        PropagationFields extractedPropagationFields = factory.create();
        extractedPropagationFields.put(FIELD2, "a");

        TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.newBuilder()
            .samplingFlags(SamplingFlags.EMPTY)
            .addExtra(extractedPropagationFields)
            .build();

        TraceContext context1 = tracing.tracer().nextSpan(extracted).context();

        assertThat(context1.extra()).hasSize(1); // merged

        PropagationFields fields = ((PropagationFields) context1.extra().get(0));
        assertThat(fields.toMap())
            .containsEntry(FIELD2, "a");
        assertThat(fields).extracting("traceId", "spanId")
            .containsExactly(context1.traceId(), context1.spanId());
      } finally {
        parent.finish();
      }
    }
  }

  @Test public void nextSpanExtraWithImplicitParent_butNoExtractedExtraFields() {
    try (Tracing tracing = Tracing.newBuilder().propagationFactory(propagationFactory).build()) {

      ScopedSpan parent = tracing.tracer().startScopedSpan("parent");
      try {
        PropagationFields.put(parent.context(), FIELD1, "1", factory.type());

        TraceContextOrSamplingFlags extracted = TraceContextOrSamplingFlags.newBuilder()
            .samplingFlags(SamplingFlags.EMPTY)
            .build();

        // TODO didn't pass the reference from parent
        TraceContext context1 = tracing.tracer().nextSpan(extracted).context();

        assertThat(context1.extra()).hasSize(1); // didn't duplicate

        PropagationFields fields = ((PropagationFields) context1.extra().get(0));
        assertThat(fields.toMap())
            .containsEntry(FIELD1, "1");
        assertThat(fields).extracting("traceId", "spanId")
            .containsExactly(context1.traceId(), context1.spanId());
      } finally {
        parent.finish();
      }
    }
  }

  @Test public void get() {
    TraceContext context =
        propagationFactory.decorate(TraceContext.newBuilder().traceId(1).spanId(2).build());
    PropagationFields.put(context, FIELD2, "a", factory.type());

    assertThat(PropagationFields.get(context, FIELD2, factory.type()))
        .isEqualTo("a");
  }

  @Test public void get_null_if_not_set() {
    assertThat(PropagationFields.get(context, FIELD2, factory.type()))
        .isNull();
  }

  @Test public void get_ignore_if_not_defined() {
    assertThat(PropagationFields.get(context, "balloon-color", factory.type()))
        .isNull();
  }

  @Test public void idempotent() {
    List<Object> originalExtra = context.extra();
    assertThat(propagationFactory.decorate(context).extra())
        .isSameAs(originalExtra);
  }

  @Test public void toSpan_selfLinksContext() {
    try (Tracing t = Tracing.newBuilder().propagationFactory(propagationFactory).build()) {
      ScopedSpan parent = t.tracer().startScopedSpan("parent");
      try {
        PropagationFields.put(parent.context(), FIELD2, "a", factory.type());

        PropagationFields fields = (PropagationFields) parent.context().extra().get(0);

        assertThat(fields).extracting("traceId", "spanId")
            .containsExactly(parent.context().traceId(), parent.context().spanId());
      } finally {
        parent.finish();
      }
    }
  }

  @Test public void toMap_one() {
    PropagationFields fields = factory.create();
    fields.put(FIELD2, "a");

    assertThat(fields.toMap())
        .hasSize(1)
        .containsEntry(FIELD2, "a");
  }

  @Test public void toMap_two() {
    PropagationFields fields = factory.create();
    fields.put(FIELD1, "1");
    fields.put(FIELD2, "a");

    assertThat(fields.toMap())
        .hasSize(2)
        .containsEntry(FIELD1, "1")
        .containsEntry(FIELD2, "a");
  }

  @Test public void toString_one() {
    PropagationFields fields = factory.create();
    fields.put(FIELD2, "a");

    assertThat(fields.toString())
        .contains("{bar=a}");
  }

  @Test public void toString_two() {
    PropagationFields fields = factory.create();
    fields.put(FIELD1, "1");
    fields.put(FIELD2, "a");

    assertThat(fields.toString())
        .contains("{foo=1, bar=a}");
  }
}
