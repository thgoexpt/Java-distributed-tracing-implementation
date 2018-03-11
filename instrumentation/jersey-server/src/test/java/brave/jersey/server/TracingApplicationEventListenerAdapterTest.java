package brave.jersey.server;

import brave.jersey.server.TracingApplicationEventListener.Adapter;
import java.net.URI;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.ContainerResponse;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class TracingApplicationEventListenerAdapterTest {
  Adapter adapter = new Adapter();
  @Mock ContainerRequest request;
  @Mock ContainerResponse response;

  @Test public void methodFromResponse() {
    when(response.getRequestContext()).thenReturn(request);
    when(request.getMethod()).thenReturn("GET");

    assertThat(adapter.methodFromResponse(response))
        .isEqualTo("GET");
  }

  @Test public void path_prefixesSlashWhenMissing() {
    when(request.getPath(false)).thenReturn("bar");

    assertThat(adapter.path(request))
        .isEqualTo("/bar");
  }

  @Test public void route() {
    when(response.getRequestContext()).thenReturn(request);
    when(request.getProperty("http.route")).thenReturn("/items/{itemId}");

    assertThat(adapter.route(response))
        .isEqualTo("/items/{itemId}");
  }

  @Test public void url_derivedFromExtendedUriInfo() {
    ExtendedUriInfo uriInfo = mock(ExtendedUriInfo.class);
    when(request.getUriInfo()).thenReturn(uriInfo);
    when(uriInfo.getRequestUri()).thenReturn(URI.create("http://foo:8080/bar?hello=world"));

    assertThat(adapter.url(request))
        .isEqualTo("http://foo:8080/bar?hello=world");
  }
}
