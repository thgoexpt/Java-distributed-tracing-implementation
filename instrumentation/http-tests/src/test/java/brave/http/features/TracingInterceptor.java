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
import brave.http.HttpClientHandler;
import brave.http.HttpTracing;
import java.io.IOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Example interceptor. Use the real deal brave-instrumentation-okhttp3 in real life */
final class TracingInterceptor implements Interceptor {
  final Tracer tracer;
  final HttpClientHandler<brave.http.HttpClientRequest, brave.http.HttpClientResponse> handler;

  TracingInterceptor(HttpTracing httpTracing) {
    tracer = httpTracing.tracing().tracer();
    handler = HttpClientHandler.create(httpTracing);
  }

  @Override public Response intercept(Interceptor.Chain chain) throws IOException {
    HttpClientRequest request = new HttpClientRequest(chain.request());
    Span span = handler.handleSend(request);
    HttpClientResponse response = null;
    Throwable error = null;
    try (Tracer.SpanInScope ws = tracer.withSpanInScope(span)) {
      Response result = chain.proceed(request.build());
      response = new HttpClientResponse(result);
      return result;
    } catch (IOException | RuntimeException | Error e) {
      error = e;
      throw e;
    } finally {
      handler.handleReceive(response, error, span);
    }
  }

  static final class HttpClientRequest extends brave.http.HttpClientRequest {
    final Request delegate;
    Request.Builder builder;

    HttpClientRequest(Request delegate) {
      this.delegate = delegate;
    }

    @Override public Object unwrap() {
      return delegate;
    }

    @Override public String method() {
      return delegate.method();
    }

    @Override public String path() {
      return delegate.url().encodedPath();
    }

    @Override public String url() {
      return delegate.url().toString();
    }

    @Override public String header(String name) {
      return delegate.header(name);
    }

    @Override public void header(String name, String value) {
      if (builder == null) builder = delegate.newBuilder();
      builder.header(name, value);
    }

    Request build() {
      return builder != null ? builder.build() : delegate;
    }
  }

  static final class HttpClientResponse extends brave.http.HttpClientResponse {
    final Response delegate;

    HttpClientResponse(Response delegate) {
      this.delegate = delegate;
    }

    @Override public Object unwrap() {
      return delegate;
    }

    @Override public int statusCode() {
      return delegate.code();
    }
  }
}
