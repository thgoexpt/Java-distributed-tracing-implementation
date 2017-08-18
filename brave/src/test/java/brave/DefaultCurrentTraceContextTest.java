package brave;

import brave.propagation.CurrentTraceContext;
import brave.propagation.TraceContext;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class DefaultCurrentTraceContextTest {
  CurrentTraceContext currentTraceContext = CurrentTraceContext.Default.create();
  Tracer tracer = Tracing.newBuilder().currentTraceContext(currentTraceContext).build().tracer();
  TraceContext context = tracer.newTrace().context();
  TraceContext context2 = tracer.newTrace().context();

  @After public void close(){
    Tracing.current().close();
  }

  @Test public void currentSpan_defaultsToNull() {
    assertThat(currentTraceContext.get()).isNull();
  }

  @Test public void scope_retainsContext() {
    try (CurrentTraceContext.Scope scope = currentTraceContext.newScope(context)) {
      assertThat(currentTraceContext.get())
          .isEqualTo(context);
    }
  }

  @Test public void scope_canClearScope() {
    try (CurrentTraceContext.Scope scope = currentTraceContext.newScope(context)) {
      try (CurrentTraceContext.Scope noScope = currentTraceContext.newScope(null)) {
        assertThat(currentTraceContext.get())
            .isNull();
      }

      // old context reverted
      assertThat(currentTraceContext.get())
          .isEqualTo(context);
    }
  }

  @Test public void show_inheritable_leak() throws Exception {
    currentTraceContext = CurrentTraceContext.Default.inheritable();

    ExecutorService service = Executors.newCachedThreadPool();
    try (CurrentTraceContext.Scope scope = currentTraceContext.newScope(context)) {
      assertThat(service.submit(() -> currentTraceContext.get()).get())
          .isEqualTo(context);
    }

    // notice it is still there (leaked and not cleaned up)
    assertThat(service.submit(() -> currentTraceContext.get()).get())
        .isEqualTo(context);

    service.shutdownNow();
  }

  @Test public void isnt_inheritable() throws Exception {
    ExecutorService service = Executors.newCachedThreadPool();

    try (CurrentTraceContext.Scope scope = currentTraceContext.newScope(context)) {
      assertThat(service.submit(() -> currentTraceContext.get()).get())
          .isNull();
    }

    assertThat(service.submit(() -> currentTraceContext.get()).get())
        .isNull();

    service.shutdownNow();
  }

  @Test
  public void attachesSpanInCallable() throws Exception {
    Callable<?> callable;
    try (CurrentTraceContext.Scope scope = currentTraceContext.newScope(context)) {
      callable = currentTraceContext.wrap(() -> {
        assertThat(currentTraceContext.get())
            .isEqualTo(context);
        return true;
      });
    }

    // Set another span between the time the task was made and executed.
    try (CurrentTraceContext.Scope scope2 = currentTraceContext.newScope(context2)) {
      callable.call(); // runs assertion
    }
  }

  @Test
  public void restoresSpanAfterCallable() throws Exception {
    TraceContext context0 = tracer.newTrace().context();
    try (CurrentTraceContext.Scope scope0 = currentTraceContext.newScope(context0)) {
      attachesSpanInCallable();
      assertThat(currentTraceContext.get())
          .isEqualTo(context0);
    }
  }

  @Test
  public void attachesSpanInRunnable() throws Exception {
    Runnable runnable;
    try (CurrentTraceContext.Scope scope = currentTraceContext.newScope(context)) {
      runnable = currentTraceContext.wrap(() -> {
        assertThat(currentTraceContext.get())
            .isEqualTo(context);
      });
    }

    // Set another span between the time the task was made and executed.
    try (CurrentTraceContext.Scope scope2 = currentTraceContext.newScope(context2)) {
      runnable.run(); // runs assertion
    }
  }

  @Test
  public void restoresSpanAfterRunnable() throws Exception {
    TraceContext context0 = tracer.newTrace().context();
    try (CurrentTraceContext.Scope scope0 = currentTraceContext.newScope(context0)) {
      attachesSpanInRunnable();
      assertThat(currentTraceContext.get())
          .isEqualTo(context0);
    }
  }
}
