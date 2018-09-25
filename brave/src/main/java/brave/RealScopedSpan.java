package brave;

import brave.firehose.FirehoseHandler;
import brave.firehose.MutableSpan;
import brave.internal.recorder.PendingSpans;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;

/** This wraps the public api and guards access to a mutable span. */
final class RealScopedSpan extends ScopedSpan {

  final TraceContext context;
  final Scope scope;
  final MutableSpan state;
  final Clock clock;
  final PendingSpans pendingSpans;
  final FirehoseHandler firehoseHandler;

  RealScopedSpan(
      TraceContext context,
      Scope scope,
      MutableSpan state,
      Clock clock,
      PendingSpans pendingSpans,
      FirehoseHandler firehoseHandler
  ) {
    this.context = context;
    this.scope = scope;
    this.pendingSpans = pendingSpans;
    this.state = state;
    this.clock = clock;
    this.firehoseHandler = firehoseHandler;
  }

  @Override public boolean isNoop() {
    return false;
  }

  @Override public TraceContext context() {
    return context;
  }

  @Override public ScopedSpan annotate(String value) {
    state.annotate(clock.currentTimeMicroseconds(), value);
    return this;
  }

  @Override public ScopedSpan tag(String key, String value) {
    state.tag(key, value);
    return this;
  }

  @Override public ScopedSpan error(Throwable throwable) {
    state.error(throwable);
    return this;
  }

  @Override public void finish() {
    scope.close();
    if (!pendingSpans.remove(context)) return; // don't double-report
    state.finishTimestamp(clock.currentTimeMicroseconds());
    firehoseHandler.handle(context, state);
  }

  @Override public boolean equals(Object o) {
    if (o == this) return true;
    if (!(o instanceof RealScopedSpan)) return false;
    RealScopedSpan that = (RealScopedSpan) o;
    return context.equals(that.context) && scope.equals(that.scope);
  }

  @Override public int hashCode() {
    int h = 1;
    h *= 1000003;
    h ^= context.hashCode();
    h *= 1000003;
    h ^= scope.hashCode();
    return h;
  }
}
