/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brave.netty.http;

import brave.http.HttpServerAdapter;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

final class HttpNettyAdapter extends HttpServerAdapter<HttpRequest, HttpResponse> {
  @Override public String method(HttpRequest request) {
    return request.method().name();
  }

  @Override public String url(HttpRequest request) {
    String host = requestHeader(request, "Host");
    if (host == null) return null;
    // TODO: we don't know if this is really http or https!
    return "http://" + host + request.uri();
  }

  @Override public String requestHeader(HttpRequest request, String name) {
    return request.headers().get(name);
  }

  @Override public Integer statusCode(HttpResponse response) {
    return statusCodeAsInt(response);
  }

  @Override public int statusCodeAsInt(HttpResponse response) {
    return response.status().code();
  }
}
