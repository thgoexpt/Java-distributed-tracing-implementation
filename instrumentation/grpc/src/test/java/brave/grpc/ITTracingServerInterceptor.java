package brave.grpc;

import brave.SpanCustomizer;
import brave.Tracing;
import brave.context.log4j2.ThreadContextCurrentTraceContext;
import brave.propagation.StrictCurrentTraceContext;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Metadata.Key;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.StatusRuntimeException;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import zipkin2.Span;

import static brave.grpc.GreeterImpl.HELLO_REQUEST;
import static io.grpc.Metadata.ASCII_STRING_MARSHALLER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

public class ITTracingServerInterceptor {
  Logger testLogger = LogManager.getLogger();

  @Rule public ExpectedException thrown = ExpectedException.none();

  ConcurrentLinkedDeque<Span> spans = new ConcurrentLinkedDeque<>();

  GrpcTracing grpcTracing;
  Server server;
  ManagedChannel client;

  @Before public void setup() throws Exception {
    grpcTracing = GrpcTracing.create(tracingBuilder(Sampler.ALWAYS_SAMPLE).build());
    init();
  }

  void init() throws Exception {
    init(null);
  }

  void init(@Nullable ServerInterceptor userInterceptor) throws Exception {
    stop();

    // tracing interceptor needs to go last
    ServerInterceptor tracingInterceptor = grpcTracing.newServerInterceptor();
    ServerInterceptor[] interceptors = userInterceptor != null
        ? new ServerInterceptor[] {userInterceptor, tracingInterceptor}
        : new ServerInterceptor[] {tracingInterceptor};

    server = ServerBuilder.forPort(PickUnusedPort.get())
        .addService(ServerInterceptors.intercept(new GreeterImpl(grpcTracing), interceptors))
        .build().start();

    client = ManagedChannelBuilder.forAddress("localhost", server.getPort())
        .usePlaintext(true)
        .build();
  }

  @After public void stop() throws Exception {
    if (client != null) {
      client.shutdown();
      client.awaitTermination(1, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.shutdown();
      server.awaitTermination();
    }
    Tracing current = Tracing.current();
    if (current != null) current.close();
  }

  @Test public void usesExistingTraceId() throws Exception {
    final String traceId = "463ac35c9f6413ad";
    final String parentId = traceId;
    final String spanId = "48485a3953bb6124";

    Channel channel = ClientInterceptors.intercept(client, new ClientInterceptor() {
      @Override
      public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
          MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
          @Override
          public void start(Listener<RespT> responseListener, Metadata headers) {
            headers.put(Key.of("X-B3-TraceId", ASCII_STRING_MARSHALLER), traceId);
            headers.put(Key.of("X-B3-ParentSpanId", ASCII_STRING_MARSHALLER), parentId);
            headers.put(Key.of("X-B3-SpanId", ASCII_STRING_MARSHALLER), spanId);
            super.start(responseListener, headers);
          }
        };
      }
    });

    GreeterGrpc.newBlockingStub(channel).sayHello(HELLO_REQUEST);

    assertThat(spans).allSatisfy(s -> {
      assertThat(s.traceId()).isEqualTo(traceId);
      assertThat(s.parentId()).isEqualTo(parentId);
      assertThat(s.id()).isEqualTo(spanId);
    });
  }

  @Test public void samplingDisabled() throws Exception {
    grpcTracing = GrpcTracing.create(tracingBuilder(Sampler.NEVER_SAMPLE).build());
    init();

    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(spans)
        .isEmpty();
  }

  /**
   * NOTE: for this to work, the tracing interceptor must be last (so that it executes first)
   *
   * <p>Also notice that we are only making the current context available in the request side.
   */
  @Test public void currentSpanVisibleToUserInterceptors() throws Exception {
    AtomicReference<TraceContext> fromUserInterceptor = new AtomicReference<>();
    init(new ServerInterceptor() {
      @Override
      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
          Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        testLogger.info("in span!");
        fromUserInterceptor.set(grpcTracing.tracing().currentTraceContext().get());
        return next.startCall(call, headers);
      }
    });

    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(fromUserInterceptor.get())
        .isNotNull();
  }

  @Test public void currentSpanVisibleToImpl() throws Exception {
    assertThat(GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST).getMessage())
        .isNotEmpty();
  }

  @Test public void reportsServerKindToZipkin() throws Exception {
    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(spans)
        .extracting(Span::kind)
        .containsExactly(Span.Kind.SERVER);
  }

  @Test public void defaultSpanNameIsMethodName() throws Exception {
    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(spans)
        .extracting(Span::name)
        .containsExactly("helloworld.greeter/sayhello");
  }

  @Test public void addsErrorTagOnException() throws Exception {
    try {
      GreeterGrpc.newBlockingStub(client)
          .sayHello(HelloRequest.newBuilder().setName("bad").build());
      failBecauseExceptionWasNotThrown(StatusRuntimeException.class);
    } catch (StatusRuntimeException e) {
      assertThat(spans).flatExtracting(s -> s.tags().entrySet()).containsExactly(
          entry("error", "UNKNOWN"),
          entry("grpc.status_code", "UNKNOWN")
      );
    }
  }

  @Test
  public void serverParserTest() throws Exception {
    grpcTracing = grpcTracing.toBuilder().serverParser(new GrpcServerParser() {
      @Override protected <M> void onMessageSent(M message, SpanCustomizer span) {
        span.tag("grpc.message_sent", message.toString());
      }

      @Override protected <M> void onMessageReceived(M message, SpanCustomizer span) {
        span.tag("grpc.message_received", message.toString());
      }

      @Override
      protected <ReqT, RespT> String spanName(MethodDescriptor<ReqT, RespT> methodDescriptor) {
        return methodDescriptor.getType().name();
      }
    }).build();
    init();

    GreeterGrpc.newBlockingStub(client).sayHello(HELLO_REQUEST);

    assertThat(spans.getFirst().name()).isEqualTo("unary");
    assertThat(spans).flatExtracting(s -> s.tags().keySet()).containsExactlyInAnyOrder(
        "grpc.message_received", "grpc.message_sent"
    );
  }

  @Test public void serverParserTestWithStreamingResponse() throws Exception {
    grpcTracing = grpcTracing.toBuilder().serverParser(new GrpcServerParser() {
      int responsesSent = 0;

      @Override protected <M> void onMessageSent(M message, SpanCustomizer span) {
        span.tag("grpc.message_sent." + responsesSent++, message.toString());
      }
    }).build();
    init();

    Iterator<HelloReply> replies = GreeterGrpc.newBlockingStub(client)
        .sayHelloWithManyReplies(HELLO_REQUEST);
    assertThat(replies).hasSize(10);
    assertThat(spans).hasSize(1);
    // all response messages are tagged to the same span
    assertThat(spans.getFirst().tags()).hasSize(10);
  }

  Tracing.Builder tracingBuilder(Sampler sampler) {
    return Tracing.newBuilder()
        .spanReporter(spans::add)
        .currentTraceContext( // connect to log4
            ThreadContextCurrentTraceContext.create(new StrictCurrentTraceContext()))
        .sampler(sampler);
  }
}
