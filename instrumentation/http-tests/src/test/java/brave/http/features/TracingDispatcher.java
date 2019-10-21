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
package brave.http.features;

import brave.Span;
import brave.Tracer;
import brave.http.HttpServerHandler;
import brave.http.HttpTracing;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;

final class TracingDispatcher extends Dispatcher {
  final Dispatcher delegate;
  final Tracer tracer;
  final HttpServerHandler<brave.http.HttpServerRequest, brave.http.HttpServerResponse> handler;

  TracingDispatcher(HttpTracing httpTracing, Dispatcher delegate) {
    tracer = httpTracing.tracing().tracer();
    handler = HttpServerHandler.create(httpTracing);
    this.delegate = delegate;
  }

  @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
    Span span = handler.handleReceive(new HttpServerRequest(request));
    MockResponse response = null;
    Throwable error = null;
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      return response = delegate.dispatch(request);
    } catch (InterruptedException | RuntimeException | Error e) {
      error = e;
      throw e;
    } finally {
      handler.handleSend(new HttpServerResponse(response), error, span);
    }
  }

  static final class HttpServerRequest extends brave.http.HttpServerRequest {
    final RecordedRequest delegate;

    HttpServerRequest(RecordedRequest delegate) {
      this.delegate = delegate;
    }

    @Override public Object unwrap() {
      return delegate;
    }

    @Override public String method() {
      return delegate.getMethod();
    }

    @Override public String path() {
      return delegate.getPath();
    }

    @Override public String url() {
      return delegate.getRequestUrl().toString();
    }

    @Override public String header(String name) {
      return delegate.getHeader(name);
    }
  }

  static final class HttpServerResponse extends brave.http.HttpServerResponse {
    final MockResponse delegate;

    HttpServerResponse(MockResponse delegate) {
      this.delegate = delegate;
    }

    @Override public Object unwrap() {
      return delegate;
    }

    @Override public int statusCode() {
      return Integer.parseInt(delegate.getStatus().split(" ")[1]);
    }
  }
}
