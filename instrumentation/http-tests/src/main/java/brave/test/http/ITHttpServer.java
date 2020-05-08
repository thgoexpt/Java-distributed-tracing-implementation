/*
 * Copyright 2013-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.test.http;

import brave.SpanCustomizer;
import brave.baggage.BaggageField;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.http.HttpAdapter;
import brave.http.HttpRequest;
import brave.http.HttpRequestParser;
import brave.http.HttpResponseParser;
import brave.http.HttpRuleSampler;
import brave.http.HttpServerParser;
import brave.http.HttpTags;
import brave.http.HttpTracing;
import brave.propagation.B3SingleFormat;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import brave.sampler.SamplerFunction;
import brave.test.ITRemote;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Test;
import zipkin2.Endpoint;
import zipkin2.Span;

import static brave.http.HttpRequestMatchers.pathStartsWith;
import static brave.sampler.Sampler.NEVER_SAMPLE;
import static org.assertj.core.api.Assertions.assertThat;

public abstract class ITHttpServer extends ITRemote {
  public static final IllegalStateException NOT_READY_ISE = new IllegalStateException("not ready") {
    @Override public Throwable fillInStackTrace() {
      return this; // don't fill logs as we are only testing
    }
  };

  OkHttpClient client = new OkHttpClient();
  protected HttpTracing httpTracing = HttpTracing.create(tracing);

  @Before public void setup() throws IOException {
    init();
  }

  /** recreate the server if needed */
  protected abstract void init() throws IOException;

  protected abstract String url(String path);

  @Test public void reusesPropagatedSpanId() throws IOException {
    String path = "/foo";

    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    get(new Request.Builder().url(url(path))
      .header("b3", B3SingleFormat.writeB3SingleFormat(parent))
      .build());

    assertSameIds(reporter.takeRemoteSpan(Span.Kind.SERVER), parent);
  }

  @Test public void createsChildWhenJoinDisabled() throws IOException {
    tracing = tracingBuilder(NEVER_SAMPLE).supportsJoin(false).build();
    httpTracing = HttpTracing.create(tracing);
    init();

    String path = "/foo";

    TraceContext parent = newTraceContext(SamplingFlags.SAMPLED);
    get(new Request.Builder().url(url(path))
      .header("b3", B3SingleFormat.writeB3SingleFormat(parent))
      .build());

    Span span = reporter.takeRemoteSpan(Span.Kind.SERVER);
    assertChildOf(span, parent);
    assertThat(span.id()).isNotEqualTo(parent.spanIdString());
  }

  @Test
  public void readsBaggage_newTrace() throws IOException {
    readsBaggage(new Request.Builder());

    reporter.takeRemoteSpan(Span.Kind.SERVER);
  }

  @Test public void readsBaggage_unsampled() throws IOException {
    readsBaggage(new Request.Builder()
      .header("X-B3-Sampled", "0"));

    // @After will check that nothing is reported
  }

  @Test public void readsBaggage_existingTrace() throws IOException {
    String traceId = "463ac35c9f6413ad";

    readsBaggage(new Request.Builder()
      .header("X-B3-TraceId", traceId)
      .header("X-B3-SpanId", traceId));

    Span span = reporter.takeRemoteSpan(Span.Kind.SERVER);
    assertThat(span.traceId()).isEqualTo(traceId);
    assertThat(span.id()).isEqualTo(traceId);
  }

  /**
   * The /baggage endpoint should copy the value of {@link #BAGGAGE_FIELD} to the response body
   * using {@link BaggageField#getValue()}.
   */
  void readsBaggage(Request.Builder builder) throws IOException {
    Request request = builder.url(url("/baggage"))
      // this is the pre-configured key we can pass through
      .header(BAGGAGE_FIELD_KEY, "joey").build();

    Response response = get(request);
    assertThat(response.isSuccessful()).isTrue();
    // if we can read the response header, the server must have been able to copy it
    assertThat(response.body().source().readUtf8())
      .isEqualTo("joey");
  }

  @Test
  public void samplingDisabled() throws IOException {
    httpTracing = HttpTracing.create(tracingBuilder(Sampler.NEVER_SAMPLE).build());
    init();

    get("/foo");

    // @After will check that nothing is reported
  }

  @Test public void customSampler() throws IOException {
    String path = "/foo";

    SamplerFunction<HttpRequest> sampler = HttpRuleSampler.newBuilder()
      .putRule(pathStartsWith(path), Sampler.NEVER_SAMPLE)
      .build();

    httpTracing = httpTracing.toBuilder().serverSampler(sampler).build();
    init();

    Request request = new Request.Builder().url(url(path)).build();
    try (Response response = client.newCall(request).execute()) {
      assertThat(response.isSuccessful()).isTrue();
    }

    // @After will check that nothing is reported
  }

  /**
   * Tests that the span propagates between under asynchronous callbacks (even if explicitly)
   */
  @Test public void async() throws IOException {
    Response response = get("/async");
    assertThat(response.isSuccessful()).withFailMessage("not successful: " + response).isTrue();

    reporter.takeRemoteSpan(Span.Kind.SERVER);
  }

  /**
   * This ensures thread-state is propagated from trace interceptors to user code. The endpoint
   * "/child" is expected to create an in-process span. When this works, it should be a child of the
   * "current span", in this case the span representing an incoming server request. When thread
   * state isn't managed properly, the child span will appear as a new trace.
   */
  @Test public void createsChildSpan() throws IOException {
    get("/child");

    Span child = reporter.takeLocalSpan();
    Span server = reporter.takeRemoteSpan(Span.Kind.SERVER);
    assertChildOf(child, server);
    assertThat(child.name()).isEqualTo("child"); // sanity check
  }

  /**
   * The child completes before the response code is established, so it should be contained
   * completely by the server's interval.
   */
  @Test public void childCompletesBeforeServer() throws IOException {
    get("/child");

    Span child = reporter.takeLocalSpan();
    Span server = reporter.takeRemoteSpan(Span.Kind.SERVER);
    assertChildOf(child, server);
    assertThat(child.name()).isEqualTo("child"); // sanity check

    long begin = server.timestampAsLong(), end = begin + server.durationAsLong();
    assertSpanInInterval(child, begin, end);
  }

  @Test public void reportsClientAddress() throws IOException {
    get("/foo");

    assertThat(reporter.takeRemoteSpan(Span.Kind.SERVER).remoteEndpoint())
      .isNotNull();
  }

  @Test public void reportsClientAddress_XForwardedFor() throws IOException {
    get(new Request.Builder().url(url("/foo"))
      .header("X-Forwarded-For", "1.2.3.4")
      .build());

    assertThat(reporter.takeRemoteSpan(Span.Kind.SERVER).remoteEndpoint())
      .extracting(Endpoint::ipv4)
      .isEqualTo("1.2.3.4");
  }

  @Test public void reportsServerKindToZipkin() throws IOException {
    get("/foo");

    reporter.takeRemoteSpan(Span.Kind.SERVER);
  }

  @Test public void defaultSpanNameIsMethodNameOrRoute() throws IOException {
    get("/foo");

    Span span = reporter.takeRemoteSpan(Span.Kind.SERVER);
    if (!span.name().equals("get")) {
      assertThat(span.name())
        .isEqualTo("get /foo");
    }
  }

  @Test public void readsRequestAtResponseTime() throws IOException {
    httpTracing = httpTracing.toBuilder()
      .serverResponseParser((response, context, span) -> {
        HttpTags.URL.tag(response.request(), span); // just the path is tagged by default
      })
      .build();
    init();

    String uri = "/foo?z=2&yAA=1";
    get(uri);

    assertThat(reporter.takeRemoteSpan(Span.Kind.SERVER).tags())
      .containsEntry("http.url", url(uri));
  }

  @Test public void supportsPortableCustomization() throws IOException {
    httpTracing = httpTracing.toBuilder()
      .serverRequestParser((request, context, span) -> {
        span.name(request.method().toLowerCase() + " " + request.path());
        HttpTags.URL.tag(request, span); // just the path is tagged by default
        span.tag("request_customizer.is_span", (span instanceof brave.Span) + "");
      })
      .serverResponseParser((response, context, span) -> {
        HttpResponseParser.DEFAULT.parse(response, context, span);
        span.tag("response_customizer.is_span", (span instanceof brave.Span) + "");
      })
      .build();
    init();

    String uri = "/foo?z=2&yAA=1";
    get(uri);

    assertThat(reporter.takeRemoteSpan(Span.Kind.SERVER).tags())
      .containsEntry("http.url", url(uri))
      .containsEntry("request_customizer.is_span", "false")
      .containsEntry("response_customizer.is_span", "false");
  }

  @Test @Deprecated public void supportsPortableCustomizationDeprecated() throws IOException {
    httpTracing = httpTracing.toBuilder().serverParser(new HttpServerParser() {
      @Override
      public <Req> void request(HttpAdapter<Req, ?> adapter, Req req, SpanCustomizer customizer) {
        customizer.tag("http.url", adapter.url(req)); // just the path is tagged by default
        customizer.tag("context.visible", String.valueOf(currentTraceContext.get() != null));
        customizer.tag("request_customizer.is_span", (customizer instanceof brave.Span) + "");
      }

      @Override
      public <Resp> void response(HttpAdapter<?, Resp> adapter, Resp res, Throwable error,
        SpanCustomizer customizer) {
        super.response(adapter, res, error, customizer);
        customizer.tag("response_customizer.is_span", (customizer instanceof brave.Span) + "");
      }
    }).build();
    init();

    String uri = "/foo?z=2&yAA=1";
    get(uri);

    assertThat(reporter.takeRemoteSpan(Span.Kind.SERVER).tags())
      .containsEntry("http.url", url(uri))
      .containsEntry("context.visible", "true")
      .containsEntry("request_customizer.is_span", "false")
      .containsEntry("response_customizer.is_span", "false");
  }

  /**
   * The "/items/{itemId}" endpoint should return the itemId in the response body, which proves
   * templating worked (including that it ignores query parameters). Note the route format is
   * framework specific, ex "/items/:itemId" in vert.x
   */
  @Test public void httpRoute() throws IOException {
    httpTracing = httpTracing.toBuilder().serverRequestParser(addHttpUrlTag).build();
    init();

    routeBasedRequestNameIncludesPathPrefix("/items");
  }

  /**
   * The "/nested/items/{itemId}" endpoint should be implemented by two route expressions: A path
   * prefix: "/nested" and then a relative expression "/items/{itemId}"
   */
  @Test public void httpRoute_nested() throws IOException {
    httpTracing = httpTracing.toBuilder().serverRequestParser(addHttpUrlTag).build();
    init();

    routeBasedRequestNameIncludesPathPrefix("/nested/items");
  }

  /**
   * Sometimes state used to carry http route data is different for async requests. This helps
   * ensure we don't miss issues like this.
   */
  @Test public void httpRoute_async() throws IOException {
    httpTracing = httpTracing.toBuilder().serverRequestParser(addHttpUrlTag).build();
    init();

    routeBasedRequestNameIncludesPathPrefix("/async_items");
  }

  private void routeBasedRequestNameIncludesPathPrefix(String prefix) throws IOException {
    Response request1 = get(prefix + "/1?foo");
    Response request2 = get(prefix + "/2?bar");

    // get() doesn't check the response, check to make sure the server didn't 500
    assertThat(request1.isSuccessful()).isTrue();
    assertThat(request2.isSuccessful()).isTrue();

    // Reading the route parameter from the response ensures the test endpoint is correct
    assertThat(request1.body().string())
      .isEqualTo("1");
    assertThat(request2.body().string())
      .isEqualTo("2");

    Span span1 = reporter.takeRemoteSpan(Span.Kind.SERVER), span2 =
      reporter.takeRemoteSpan(Span.Kind.SERVER);

    // verify that the path and url reflect the initial request (not a route expression)
    assertThat(span1.tags())
      .containsEntry("http.method", "GET")
      .containsEntry("http.path", prefix + "/1")
      .containsEntry("http.url", url(prefix + "/1?foo"));
    assertThat(span2.tags())
      .containsEntry("http.method", "GET")
      .containsEntry("http.path", prefix + "/2")
      .containsEntry("http.url", url(prefix + "/2?bar"));

    // We don't know the exact format of the http route as it is framework specific
    // However, we know that it should match both requests and include the common part of the path
    Set<String> routeBasedNames = new LinkedHashSet<>(Arrays.asList(span1.name(), span2.name()));
    assertThat(routeBasedNames).hasSize(1);
    assertThat(routeBasedNames.iterator().next())
      .startsWith("get " + prefix)
      .doesNotEndWith("/") // no trailing slashes
      .doesNotContain("//"); // no duplicate slashes
  }

  final HttpRequestParser addHttpUrlTag = (request, context, span) -> {
    HttpRequestParser.DEFAULT.parse(request, context, span);
    HttpTags.URL.tag(request, span); // just the path is tagged by default
  };

  /** If http route is supported, then the span name should include it */
  @Test public void notFound() throws IOException {
    // we can't use get("/foo/bark") because get(path) throws assumption fail on 404
    assertThat(call("GET", "/foo/bark").code())
      .isEqualTo(404);

    Span span = reporter.takeRemoteSpanWithError(Span.Kind.SERVER, "404");

    // verify normal tags
    assertThat(span.tags())
      .hasSize(4)
      .containsEntry("http.method", "GET")
      .containsEntry("http.path", "/foo/bark")
      .containsEntry("http.status_code", "404");

    // Either the span name is the method, or it is a route expression
    String name = span.name();
    if (name != null && !"get".equals(name)) {
      assertThat(name).isEqualTo("get not_found");
    }
  }

  /**
   * This tests both that a root path ends up as "/" (slash) not "" (empty), as well that less
   * typical OPTIONS methods can be traced.
   */
  @Test public void options() throws IOException {
    assertThat(call("OPTIONS", "/").isSuccessful())
      .isTrue();

    Span span = reporter.takeRemoteSpan(Span.Kind.SERVER);

    // verify normal tags
    assertThat(span.tags())
      .containsEntry("http.method", "OPTIONS")
      .containsEntry("http.path", "/");

    // Either the span name is the method, or it is a route expression
    String name = span.name();
    if (name != null && !"options".equals(name)) {
      assertThat(name).isEqualTo("options /");
    }
  }

  @Test public void addsStatusCode_badRequest() throws IOException {
    try {
      get("/badrequest");
    } catch (RuntimeException e) {
      // some servers think 400 is an error
    }

    assertThat(reporter.takeRemoteSpanWithError(Span.Kind.SERVER, "400").tags())
      .containsEntry("error", "400");
  }

  @Test public void httpPathTagExcludesQueryParams() throws IOException {
    get("/foo?z=2&yAA=1");

    assertThat(reporter.takeRemoteSpan(Span.Kind.SERVER).tags())
      .containsEntry("http.path", "/foo");
  }

  /**
   * Throw {@link ITHttpServer#NOT_READY_ISE} inside your controller unless you cannot control the
   * HTTP status code. When this is the case, you may be able to use a wrapped exception such as
   * {@link ITServletContainer#NOT_READY_UE} instead.
   */
  @Test public void httpStatusCodeTagMatchesResponse_onException() throws IOException {
    httpStatusCodeTagMatchesResponse("/exception", ".+");
  }

  @Test public void httpStatusCodeTagMatchesResponse_onException_async() throws IOException {
    httpStatusCodeTagMatchesResponse("/exceptionAsync", ".+");
  }

  Span httpStatusCodeTagMatchesResponse(String path, String message) throws IOException {
    Response response = get(path);

    Span span = reporter.takeRemoteSpanWithError(Span.Kind.SERVER, message);
    assertThat(span.tags())
      .containsEntry("http.status_code", String.valueOf(response.code()));
    return span;
  }

  @Test public void errorTag_exceptionOverridesHttpStatus() throws IOException {
    httpStatusCodeTagMatchesResponse("/exception", ".*not ready");
  }

  @Test public void errorTag_exceptionOverridesHttpStatus_async() throws IOException {
    httpStatusCodeTagMatchesResponse("/exceptionAsync", ".*not ready");
  }

  @Test public void spanHandlerSeesException() throws IOException {
    spanHandlerSeesException("/exception");
  }

  @Test public void spanHandlerSeesException_async() throws IOException {
    spanHandlerSeesException("/exceptionAsync");
  }

  /**
   * This ensures custom span handlers can see the actual exception thrown, not just the "error" tag
   * value.
   */
  void spanHandlerSeesException(String path) throws IOException {
    AtomicReference<Throwable> caughtThrowable = new AtomicReference<>();
    httpTracing = HttpTracing.create(tracingBuilder(Sampler.ALWAYS_SAMPLE)
      .addSpanHandler(new SpanHandler() {
        @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
          caughtThrowable.set(span.error());
          return true;
        }
      })
      .build());
    init();

    httpStatusCodeTagMatchesResponse(path, ".*not ready");

    assertThat(caughtThrowable.get()).isNotNull();
  }

  protected Response get(String path) throws IOException {
    return get(new Request.Builder().url(url(path)).build());
  }

  protected Response get(Request request) throws IOException {
    Response response = call(request);
    if (response.code() == 404) {
      // 404 path may or may not be instrumented. Ensure the AssumptionViolatedException isn't
      // masked by a failure to take a server span.
      reporter.ignoreAnySpans();
      throw new AssumptionViolatedException(
        response.request().url().encodedPath() + " not supported"
      );
    }
    return response;
  }

  /** like {@link #get(String)} except doesn't throw unsupported on not found */
  Response call(String method, String path) throws IOException {
    return call(new Request.Builder().method(method, null).url(url(path)).build());
  }

  /** like {@link #get(Request)} except doesn't throw unsupported on not found */
  @SuppressWarnings("deprecation")
  Response call(Request request) throws IOException {
    // Particularly during async debugging, knowing which test invoked a request is helpful.
    request = request.newBuilder().header("test", testName.getMethodName()).build();

    try (Response response = client.newCall(request).execute()) {
      if (response.body() == null) return response;

      // buffer response so tests can read it. Otherwise the finally block will drop it
      ResponseBody toReturn;
      try (ResponseBody body = response.body()) {
        Buffer buffer = new Buffer();
        body.source().readAll(buffer);
        // allow deprecated method call as otherwise we pin the classpath to okhttp 4
        toReturn = ResponseBody.create(body.contentType(), body.contentLength(), buffer);
      }
      return response.newBuilder().body(toReturn).build();
    }
  }
}
