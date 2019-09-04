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
package brave.jersey.server;

import brave.jersey.server.TracingApplicationEventListener.HttpServerRequest;
import brave.jersey.server.TracingApplicationEventListener.HttpServerResponse;
import java.net.URI;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.server.monitoring.RequestEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class TracingApplicationEventListenerTest {
  @Mock ContainerRequest request;
  @Mock RequestEvent event;
  @Mock ContainerResponse response;

  @Test public void methodFromResponse() {
    when(event.getContainerRequest()).thenReturn(request);
    when(request.getMethod()).thenReturn("GET");

    assertThat(new HttpServerResponse(event).method())
      .isEqualTo("GET");
  }

  @Test public void path_prefixesSlashWhenMissing() {
    when(request.getPath(false)).thenReturn("bar");

    assertThat(new HttpServerRequest(request).path())
      .isEqualTo("/bar");
  }

  @Test public void route() {
    when(event.getContainerRequest()).thenReturn(request);
    when(request.getProperty("http.route")).thenReturn("/items/{itemId}");

    assertThat(new HttpServerResponse(event).route())
      .isEqualTo("/items/{itemId}");
  }

  @Test public void url_derivedFromExtendedUriInfo() {
    ExtendedUriInfo uriInfo = mock(ExtendedUriInfo.class);
    when(request.getUriInfo()).thenReturn(uriInfo);
    when(uriInfo.getRequestUri()).thenReturn(URI.create("http://foo:8080/bar?hello=world"));

    assertThat(new HttpServerRequest(request).url())
      .isEqualTo("http://foo:8080/bar?hello=world");
  }

  @Test public void statusCodeAsInt() {
    when(event.getContainerResponse()).thenReturn(response);
    when(response.getStatus()).thenReturn(200);

    assertThat(new HttpServerResponse(event).statusCode()).isEqualTo(200);
  }

  @Test public void statusCodeAsInt_zeroNoResponse() {
    assertThat(new HttpServerResponse(event).statusCode()).isZero();
  }
}
