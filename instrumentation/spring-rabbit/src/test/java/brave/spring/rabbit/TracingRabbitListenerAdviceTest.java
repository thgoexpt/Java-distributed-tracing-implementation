package brave.spring.rabbit;

import brave.Tracing;
import brave.propagation.ThreadLocalCurrentTraceContext;
import java.util.ArrayList;
import java.util.List;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.After;
import org.junit.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import zipkin2.Span;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static zipkin2.Span.Kind.CONSUMER;

public class TracingRabbitListenerAdviceTest {

  static String TRACE_ID = "463ac35c9f6413ad";
  static String PARENT_ID = "463ac35c9f6413ab";
  static String SPAN_ID = "48485a3953bb6124";
  static String SAMPLED = "1";

  List<Span> spans = new ArrayList<>();
  Tracing tracing = Tracing.newBuilder()
      .currentTraceContext(ThreadLocalCurrentTraceContext.create())
      .spanReporter(spans::add)
      .build();
  TracingRabbitListenerAdvice tracingRabbitListenerAdvice =
      new TracingRabbitListenerAdvice(tracing, "my-service");
  MethodInvocation methodInvocation = mock(MethodInvocation.class);

  @After public void close() {
    tracing.close();
  }

  @Test public void starts_new_trace_if_none_exists() throws Throwable {
    Message message = MessageBuilder.withBody(new byte[] {}).build();
    onMessageConsumed(message);

    assertThat(spans)
        .extracting(Span::kind)
        .containsExactly(CONSUMER, null);
  }

  @Test public void consumer_has_service_name() throws Throwable {
    Message message = MessageBuilder.withBody(new byte[] {}).build();
    onMessageConsumed(message);

    assertThat(spans)
        .extracting(Span::remoteServiceName)
        .containsExactly("my-service", null);
  }

  @Test public void continues_parent_trace() throws Throwable {
    MessageProperties props = new MessageProperties();
    props.setHeader("X-B3-TraceId", TRACE_ID);
    props.setHeader("X-B3-SpanId", SPAN_ID);
    props.setHeader("X-B3-ParentSpanId", PARENT_ID);
    props.setHeader("X-B3-Sampled", SAMPLED);

    Message message = MessageBuilder.withBody(new byte[] {})
        .andProperties(props)
        .build();
    onMessageConsumed(message);

    assertThat(spans)
        .filteredOn(span -> span.kind() == CONSUMER)
        .extracting(Span::parentId)
        .contains(SPAN_ID);
  }

  @Test public void reports_span_if_consume_fails() throws Throwable {
    Message message = MessageBuilder.withBody(new byte[] {}).build();
    onMessageConsumeFailed(message, new RuntimeException("expected exception"));

    assertThat(spans)
        .extracting(Span::kind)
        .containsExactly(CONSUMER, null);

    assertThat(spans)
        .filteredOn(span -> span.kind() == null)
        .extracting(Span::tags)
        .extracting(tags -> tags.get("error"))
        .contains("expected exception");
  }

  @Test public void reports_span_if_consume_fails_with_no_message() throws Throwable {
    Message message = MessageBuilder.withBody(new byte[] {}).build();
    onMessageConsumeFailed(message, new RuntimeException());

    assertThat(spans)
        .extracting(Span::kind)
        .containsExactly(CONSUMER, null);

    assertThat(spans)
        .filteredOn(span -> span.kind() == null)
        .extracting(Span::tags)
        .extracting(tags -> tags.get("error"))
        .contains("RuntimeException");
  }

  void onMessageConsumed(Message message) throws Throwable {
    when(methodInvocation.getArguments()).thenReturn(new Object[] {
        null, // AMQPChannel - doesn't matter
        message
    });
    when(methodInvocation.proceed()).thenReturn("doesn't matter");

    tracingRabbitListenerAdvice.invoke(methodInvocation);
  }

  void onMessageConsumeFailed(Message message, Throwable throwable) throws Throwable {
    when(methodInvocation.getArguments()).thenReturn(new Object[] {
        null, // AMQPChannel - doesn't matter
        message
    });
    when(methodInvocation.proceed()).thenThrow(throwable);

    try {
      tracingRabbitListenerAdvice.invoke(methodInvocation);
      fail("should have thrown exception");
    } catch (RuntimeException ex) {
    }
  }
}
