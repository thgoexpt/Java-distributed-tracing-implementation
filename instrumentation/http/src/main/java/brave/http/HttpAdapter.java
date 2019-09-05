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

import brave.Clock;
import brave.Span;
import brave.internal.Nullable;
import brave.propagation.TraceContext;
import java.net.URI;

public abstract class HttpAdapter<Req, Resp> {
  /**
   * The HTTP method, or verb, such as "GET" or "POST" or null if unreadable.
   *
   * <p>Conventionally associated with the key "http.method"
   */
  @Nullable public abstract String method(Req request);

  /**
   * The absolute http path, without any query parameters or null if unreadable. Ex.
   * "/objects/abcd-ff"
   *
   * <p>Conventionally associated with the key "http.path"
   *
   * @see #route(Object)
   */
  @Nullable public String path(Req request) {
    String url = url(request);
    if (url == null) return null;
    return URI.create(url).getPath(); // TODO benchmark
  }

  /**
   * The entire URL, including the scheme, host and query parameters if available or null if
   * unreadable.
   *
   * <p>Conventionally associated with the key "http.url"
   */
  @Nullable public abstract String url(Req request);

  /** Returns one value corresponding to the specified header, or null. */
  @Nullable public abstract String requestHeader(Req request, String name);

  /**
   * The timestamp in epoch microseconds of the beginning of this request or zero to take this
   * implicitly from the current clock. Defaults to zero.
   *
   * <p>This is helpful in two scenarios: late parsing and avoiding redundant timestamp overhead.
   * If a server span, this helps reach the "original" beginning of the request, which is always
   * prior to parsing.
   *
   * <p>Note: Overriding has the same problems as using {@link brave.Span#start(long)}. For
   * example, it can result in negative duration if the clock used is allowed to correct backwards.
   * It can also result in misalignments in the trace, unless {@link brave.Tracing.Builder#clock(Clock)}
   * uses the same implementation.
   *
   * @see #finishTimestamp(Object)
   * @see brave.Span#start(long)
   * @see brave.Tracing#clock(TraceContext)
   * @since 5.7
   */
  public long startTimestamp(Req request) {
    return 0L;
  }

  /**
   * Like {@link #method(Object)} except used in response parsing.
   *
   * <p>Notably, this is used to create a route-based span name.
   */
  // FromResponse suffix is needed as you can't compile methods that only differ on generic params
  @Nullable public String methodFromResponse(Resp resp) {
    return null;
  }

  /**
   * Returns an expression such as "/items/:itemId" representing an application endpoint,
   * conventionally associated with the tag key "http.route". If no route matched, "" (empty string)
   * is returned. Null indicates this instrumentation doesn't understand http routes.
   *
   * <p>Eventhough the route is associated with the request, not the response, this is present
   * on the response object. The reasons is that many server implementations process the request
   * before they can identify the route route.
   */
  // BRAVE6: It isn't possible for a user to easily consume HttpServerAdapter, which is why this
  // method, while generally about the server, is pushed up to the HttpAdapter. The signatures for
  // sampling and parsing could be changed to make it more convenient.
  @Nullable public String route(Resp response) {
    return null;
  }

  /**
   * The HTTP status code or null if unreadable.
   *
   * <p>Conventionally associated with the key "http.status_code"
   *
   * @see #statusCodeAsInt(Object)
   * @deprecated Since 5.7, use {@link #statusCodeAsInt(Object)} which prevents boxing.
   */
  @Deprecated @Nullable public abstract Integer statusCode(Resp response);

  /**
   * Like {@link #statusCode(Object)} except returns a primitive where zero implies absent.
   *
   * <p>Using this method usually avoids allocation, so is encouraged when parsing data.
   *
   * @since 4.16
   */
  public int statusCodeAsInt(Resp response) {
    Integer maybeStatus = statusCode(response);
    return maybeStatus != null ? maybeStatus : 0;
  }

  /**
   * The timestamp in epoch microseconds of the end of this request or zero to take this implicitly
   * from the current clock. Defaults to zero.
   *
   * <p>This is helpful in two scenarios: late parsing and avoiding redundant timestamp overhead.
   * For example, you can asynchronously handle span completion without losing precision of the
   * actual end.
   *
   * <p>Note: Overriding has the same problems as using {@link Span#finish(long)}. For
   * example, it can result in negative duration if the clock used is allowed to correct backwards.
   * It can also result in misalignments in the trace, unless {@link brave.Tracing.Builder#clock(Clock)}
   * uses the same implementation.
   *
   * @see #startTimestamp(Object)
   * @see brave.Span#finish(long)
   * @see brave.Tracing#clock(TraceContext)
   * @since 5.7
   */
  public long finishTimestamp(Resp response) {
    return 0L;
  }

  HttpAdapter() {
  }
}
