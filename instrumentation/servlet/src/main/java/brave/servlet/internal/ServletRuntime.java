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
package brave.servlet.internal;

import brave.Span;
import brave.http.HttpServerHandler;
import brave.servlet.HttpServletAdapter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import zipkin2.Call;

/**
 * Access to servlet version-specific features
 *
 * <p>Originally designed by OkHttp team, derived from {@code okhttp3.internal.platform.Platform}
 */
public abstract class ServletRuntime {
  private static final ServletRuntime SERVLET_RUNTIME = findServletRuntime();

  public HttpServletResponse httpServletResponse(ServletResponse response) {
    return (HttpServletResponse) response;
  }

  /** public while {@link HttpServletAdapter} exists. */
  public abstract int status(HttpServletResponse response);

  public abstract boolean isAsync(HttpServletRequest request);

  public abstract void handleAsync(
    HttpServerHandler<brave.http.HttpServerRequest, brave.http.HttpServerResponse> handler,
    HttpServletRequest request, HttpServletResponse response, Span span);

  ServletRuntime() {
  }

  public static ServletRuntime get() {
    return SERVLET_RUNTIME;
  }

  public brave.http.HttpServerResponse httpServerResponse(
    HttpServletRequest req, HttpServletResponse resp) {
    return new HttpServerResponse(req, resp, status(resp));
  }

  /** Attempt to match the host runtime to a capable Platform implementation. */
  private static ServletRuntime findServletRuntime() {
    // Find Servlet v3 new methods
    try {
      Class.forName("javax.servlet.AsyncEvent");
      HttpServletRequest.class.getMethod("isAsyncStarted");
      return new Servlet3(); // intentionally doesn't not access the type prior to the above guard
    } catch (NoSuchMethodException e) {
      // pre Servlet v3
    } catch (ClassNotFoundException e) {
      // pre Servlet v3
    }

    // compatible with Servlet 2.5
    return new Servlet25();
  }

  static final class Servlet3 extends ServletRuntime {
    @Override public boolean isAsync(HttpServletRequest request) {
      return request.isAsyncStarted();
    }

    @Override public int status(HttpServletResponse response) {
      return response.getStatus();
    }

    @Override public void handleAsync(
      HttpServerHandler<brave.http.HttpServerRequest, brave.http.HttpServerResponse> handler,
      HttpServletRequest request, HttpServletResponse response, Span span) {
      if (span.isNoop()) return; // don't add overhead when we aren't httpTracing
      TracingAsyncListener listener = new TracingAsyncListener(handler, span);
      request.getAsyncContext().addListener(listener, request, response);
    }

    static final class TracingAsyncListener implements AsyncListener {
      final HttpServerHandler<brave.http.HttpServerRequest, brave.http.HttpServerResponse> handler;
      final Span span;
      volatile boolean complete; // multiple async events can occur, only complete once

      TracingAsyncListener(
        HttpServerHandler<brave.http.HttpServerRequest, brave.http.HttpServerResponse> handler,
        Span span
      ) {
        this.handler = handler;
        this.span = span;
      }

      @Override public void onComplete(AsyncEvent e) {
        if (complete) return;
        handler.handleSend(httpServerResponse(e), null, span);
        complete = true;
      }

      @Override public void onTimeout(AsyncEvent e) {
        if (complete) return;
        span.tag("error", String.format("Timed out after %sms", e.getAsyncContext().getTimeout()));
        handler.handleSend(httpServerResponse(e), null, span);
        complete = true;
      }

      @Override public void onError(AsyncEvent e) {
        if (complete) return;
        handler.handleSend(httpServerResponse(e), e.getThrowable(), span);
        complete = true;
      }

      /** If another async is created (ex via asyncContext.dispatch), this needs to be re-attached */
      @Override public void onStartAsync(AsyncEvent e) {
        AsyncContext eventAsyncContext = e.getAsyncContext();
        if (eventAsyncContext != null) {
          eventAsyncContext.addListener(this, e.getSuppliedRequest(), e.getSuppliedResponse());
        }
      }

      @Override public String toString() {
        return "TracingAsyncListener{" + span + "}";
      }
    }

    static HttpServerResponse httpServerResponse(AsyncEvent event) {
      HttpServletRequest req = (HttpServletRequest) event.getSuppliedRequest();
      HttpServletResponse resp = (HttpServletResponse) event.getSuppliedResponse();
      return new HttpServerResponse(req, resp, resp.getStatus());
    }
  }

  static final class Servlet25 extends ServletRuntime {
    @Override public HttpServletResponse httpServletResponse(ServletResponse response) {
      return new Servlet25ServerResponseAdapter(response);
    }

    @Override public boolean isAsync(HttpServletRequest request) {
      return false;
    }

    @Override public void handleAsync(
      HttpServerHandler<brave.http.HttpServerRequest, brave.http.HttpServerResponse> handler,
      HttpServletRequest request, HttpServletResponse response, Span span) {
      assert false : "this should never be called in Servlet 2.5";
    }

    // copy-on-write global reflection cache outperforms thread local copies
    final AtomicReference<Map<Class<?>, Object>> classToGetStatus =
      new AtomicReference<>(new LinkedHashMap<>());
    static final String RETURN_NULL = "RETURN_NULL";

    /**
     * Eventhough the Servlet 2.5 version of HttpServletResponse doesn't have the getStatus method,
     * routine servlet runtimes, do, for example {@code org.eclipse.jetty.server.Response}
     */
    @Override public int status(HttpServletResponse response) {
      // unwrap if we've decorated the response
      if (response instanceof HttpServletResponseWrapper) {
        HttpServletResponseWrapper decorated = ((HttpServletResponseWrapper) response);
        response = (HttpServletResponse) decorated.getResponse();
      }
      if (response instanceof Servlet25ServerResponseAdapter) {
        // servlet 2.5 doesn't have get status
        return ((Servlet25ServerResponseAdapter) response).getStatusInServlet25();
      }
      Class<? extends HttpServletResponse> clazz = response.getClass();
      Map<Class<?>, Object> classesToCheck = classToGetStatus.get();
      Object getStatusMethod = classesToCheck.get(clazz);
      if (getStatusMethod == RETURN_NULL ||
        (getStatusMethod == null && classesToCheck.size() == 10)) { // limit size
        return 0;
      }

      // Now, we either have a cached method or we have room to cache a method
      if (getStatusMethod == null) {
        if (clazz.isLocalClass() || clazz.isAnonymousClass()) return 0; // don't cache
        try {
          // we don't check for accessibility as isAccessible is deprecated: just fail later
          getStatusMethod = clazz.getMethod("getStatus");
          return (int) ((Method) getStatusMethod).invoke(response);
        } catch (Throwable throwable) {
          Call.propagateIfFatal(throwable);
          getStatusMethod = RETURN_NULL;
          return 0;
        } finally {
          // regardless of success or fail, replace the cache
          Map<Class<?>, Object> replacement = new LinkedHashMap<>(classesToCheck);
          replacement.put(clazz, getStatusMethod);
          classToGetStatus.set(replacement); // lost race will reset, but only up to size - 1 times
        }
      }

      // if we are here, we have a cached method, that "should" never fail, but we check anyway
      try {
        return (int) ((Method) getStatusMethod).invoke(response);
      } catch (Throwable throwable) {
        Call.propagateIfFatal(throwable);
        Map<Class<?>, Object> replacement = new LinkedHashMap<>(classesToCheck);
        replacement.put(clazz, RETURN_NULL);
        classToGetStatus.set(replacement); // prefer overriding on failure
        return 0;
      }
    }
  }

  /** When deployed in Servlet 2.5 environment {@link #getStatus} is not available. */
  static final class Servlet25ServerResponseAdapter extends HttpServletResponseWrapper {
    // The Servlet spec says: calling setStatus is optional, if no status is set, the default is OK.
    int httpStatus = HttpServletResponse.SC_OK;

    Servlet25ServerResponseAdapter(ServletResponse response) {
      super((HttpServletResponse) response);
    }

    // Do not use @Override annotation to avoid compatibility on deprecated methods
    public void setStatus(int sc, String sm) {
      httpStatus = sc;
      super.setStatus(sc, sm);
    }

    @Override public void sendError(int sc) throws IOException {
      httpStatus = sc;
      super.sendError(sc);
    }

    @Override public void sendError(int sc, String msg) throws IOException {
      httpStatus = sc;
      super.sendError(sc, msg);
    }

    @Override public void setStatus(int sc) {
      httpStatus = sc;
      super.setStatus(sc);
    }

    int getStatusInServlet25() {
      return httpStatus;
    }
  }

  static final class HttpServerResponse extends brave.http.HttpServerResponse {
    final HttpServletResponse delegate;
    final String method, httpRoute;
    final int statusCode;

    HttpServerResponse(HttpServletRequest req, HttpServletResponse resp, int statusCode) {
      if (req == null) throw new NullPointerException("req == null");
      if (resp == null) throw new NullPointerException("resp == null");
      this.delegate = resp;
      this.method = req.getMethod();
      this.httpRoute = (String) req.getAttribute("http.route");
      this.statusCode = statusCode;
    }

    @Override public String method() {
      return method;
    }

    @Override public String route() {
      return httpRoute;
    }

    @Override public HttpServletResponse unwrap() {
      return delegate;
    }

    @Override public int statusCode() {
      return statusCode;
    }
  }
}
