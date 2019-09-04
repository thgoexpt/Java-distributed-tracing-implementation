/*
 * Copyright 2013-2019 The OpenZipkin Authors
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
package brave.spring.web;

import brave.Span;
import brave.Tracer;
import brave.Tracing;
import brave.http.HttpClientHandler;
import brave.http.HttpTracing;
import brave.spring.web.TracingClientHttpRequestInterceptor.HttpClientResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.AsyncClientHttpRequestExecution;
import org.springframework.http.client.AsyncClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;

public final class TracingAsyncClientHttpRequestInterceptor
  implements AsyncClientHttpRequestInterceptor {

  public static AsyncClientHttpRequestInterceptor create(Tracing tracing) {
    return create(HttpTracing.create(tracing));
  }

  public static AsyncClientHttpRequestInterceptor create(HttpTracing httpTracing) {
    return new TracingAsyncClientHttpRequestInterceptor(httpTracing);
  }

  final Tracer tracer;
  final HttpClientHandler<brave.http.HttpClientRequest, brave.http.HttpClientResponse> handler;

  @Autowired TracingAsyncClientHttpRequestInterceptor(HttpTracing httpTracing) {
    tracer = httpTracing.tracing().tracer();
    handler = HttpClientHandler.create(httpTracing);
  }

  @Override public ListenableFuture<ClientHttpResponse> intercept(HttpRequest request,
    byte[] body, AsyncClientHttpRequestExecution execution) throws IOException {
    Span span =
      handler.handleSend(new TracingClientHttpRequestInterceptor.HttpClientRequest(request));
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      ListenableFuture<ClientHttpResponse> result = execution.executeAsync(request, body);
      result.addCallback(new TraceListenableFutureCallback(span, handler));
      return result;
    } catch (IOException | RuntimeException | Error e) {
      handler.handleReceive(null, e, span);
      throw e;
    }
  }

  static final class TraceListenableFutureCallback
    implements ListenableFutureCallback<ClientHttpResponse> {
    final Span span;
    final HttpClientHandler<brave.http.HttpClientRequest, brave.http.HttpClientResponse> handler;

    TraceListenableFutureCallback(Span span,
      HttpClientHandler<brave.http.HttpClientRequest, brave.http.HttpClientResponse> handler) {
      this.span = span;
      this.handler = handler;
    }

    @Override public void onFailure(Throwable ex) {
      handler.handleReceive(null, ex, span);
    }

    @Override public void onSuccess(ClientHttpResponse result) {
      handler.handleReceive(new HttpClientResponse(result), null, span);
    }
  }
}
