package brave;

import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;
import zipkin2.Annotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class RealSpanTest {
  List<zipkin2.Span> spans = new ArrayList();
  Tracer tracer = Tracing.newBuilder().spanReporter(spans::add).build().tracer();
  Span span = tracer.newTrace();

  @After public void close() {
    Tracing.current().close();
  }

  @Test public void isNotNoop() {
    assertThat(span.isNoop()).isFalse();
  }

  @Test public void hasRealContext() {
    assertThat(span.context().spanId()).isNotZero();
  }

  @Test public void start() {
    span.start();
    span.flush();

    assertThat(spans).hasSize(1).first()
        .extracting(zipkin2.Span::timestamp)
        .isNotNull();
  }

  @Test public void start_timestamp() {
    span.start(2);
    span.flush();

    assertThat(spans).hasSize(1).first()
        .extracting(zipkin2.Span::timestamp)
        .containsExactly(2L);
  }

  @Test public void finish() {
    span.start();
    span.finish();

    assertThat(spans).hasSize(1).first()
        .extracting(zipkin2.Span::duration)
        .isNotNull();
  }

  @Test public void finish_timestamp() {
    span.start(2);
    span.finish(5);

    assertThat(spans).hasSize(1).first()
        .extracting(zipkin2.Span::duration)
        .containsExactly(3L);
  }

  @Test public void abandon() {
    span.start();
    span.abandon();

    assertThat(spans).hasSize(0);
  }

  @Test public void annotate() {
    span.annotate("foo");
    span.flush();

    assertThat(spans).flatExtracting(zipkin2.Span::annotations)
        .extracting(Annotation::value)
        .containsExactly("foo");
  }

  @Test public void annotate_timestamp() {
    span.annotate(2, "foo");
    span.flush();

    assertThat(spans).flatExtracting(zipkin2.Span::annotations)
        .containsExactly(Annotation.create(2L, "foo"));
  }

  @Test public void tag() {
    span.tag("foo", "bar");
    span.flush();

    assertThat(spans).flatExtracting(s -> s.tags().entrySet())
        .containsExactly(entry("foo", "bar"));
  }

  @Test public void doubleFinishDoesntDoubleReport() {
    Span span = tracer.newTrace().name("foo").start();

    span.finish();
    span.finish();

    assertThat(spans).hasSize(1);
  }

  @Test public void finishAfterAbandonDoesntReport() {
    span.start();
    span.abandon();
    span.finish();

    assertThat(spans).hasSize(0);
  }

  @Test public void abandonAfterFinishDoesNothing() {
    span.start();
    span.finish();
    span.abandon();

    assertThat(spans).hasSize(1);
  }
}
