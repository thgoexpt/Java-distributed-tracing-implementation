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
package brave.http;

import brave.Span;
import brave.internal.Nullable;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;

abstract class HttpHandler {
  final CurrentTraceContext currentTraceContext;
  final HttpParser parser;

  HttpHandler(CurrentTraceContext currentTraceContext, HttpParser parser) {
    this.currentTraceContext = currentTraceContext;
    this.parser = parser;
  }

  <Req> Span handleStart(HttpAdapter<Req, ?> adapter, Req request, Span span) {
    if (span.isNoop()) return span;
    Scope ws = currentTraceContext.maybeScope(span.context());
    try {
      parseRequest(adapter, request, span);
    } finally {
      ws.close();
    }

    // all of the above parsing happened before a timestamp on the span
    long timestamp = adapter.startTimestamp(request);
    if (timestamp == 0L) {
      span.start();
    } else {
      span.start(timestamp);
    }
    return span;
  }

  /** parses remote IP:port and tags while the span is in scope (for logging for example) */
  abstract <Req> void parseRequest(HttpAdapter<Req, ?> adapter, Req request, Span span);

  <Resp> void handleFinish(HttpAdapter<?, Resp> adapter, @Nullable Resp response,
    @Nullable Throwable error, Span span) {
    if (span.isNoop()) return;
    long finishTimestamp = response != null ? adapter.finishTimestamp(response) : 0L;
    try {
      Scope ws = currentTraceContext.maybeScope(span.context());
      try {
        parser.response(adapter, response, error, span.customizer());
      } finally {
        ws.close(); // close the scope before finishing the span
      }
    } finally {
      finishInNullScope(span, finishTimestamp);
    }
  }

  /** Clears the scope to prevent remote reporters from accidentally tracing */
  void finishInNullScope(Span span, long timestamp) {
    Scope ws = currentTraceContext.maybeScope(null);
    try {
      if (timestamp == 0L) {
        span.finish();
      } else {
        span.finish(timestamp);
      }
    } finally {
      ws.close();
    }
  }
}
