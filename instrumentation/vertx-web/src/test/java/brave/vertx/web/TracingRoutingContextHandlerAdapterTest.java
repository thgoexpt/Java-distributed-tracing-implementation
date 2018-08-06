package brave.vertx.web;

import brave.vertx.web.TracingRoutingContextHandler.Adapter;
import brave.vertx.web.TracingRoutingContextHandler.Route;
import io.vertx.core.http.HttpServerResponse;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;

@RunWith(MockitoJUnitRunner.class)
public class TracingRoutingContextHandlerAdapterTest {
  ThreadLocal<Route> currentRoute = new ThreadLocal<>();
  Adapter adapter = new Adapter(currentRoute);
  @Mock HttpServerResponse response;

  @After public void clear() {
    currentRoute.remove();
  }

  @Test public void methodFromResponse() {
    currentRoute.set(new Route("GET", null));
    assertThat(adapter.methodFromResponse(response))
        .isEqualTo("GET");
  }

  @Test public void route_emptyByDefault() {
    currentRoute.set(new Route("GET", null));
    assertThat(adapter.route(response)).isEmpty();
  }

  @Test public void route() {
    currentRoute.set(new Route("GET", "/users/:userID"));
    assertThat(adapter.route(response))
        .isEqualTo("/users/:userID");
  }
}
