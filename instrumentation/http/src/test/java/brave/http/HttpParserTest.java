package brave.http;

import brave.SpanCustomizer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class HttpParserTest {
  @Mock HttpClientAdapter<Object, Object> adapter;
  @Mock SpanCustomizer customizer;
  Object request = new Object();
  Object response = new Object();
  HttpParser parser = new HttpParser();

  @Test public void spanName_isMethod() {
    when(adapter.method(request)).thenReturn("GET");

    assertThat(parser.spanName(adapter, request))
        .isEqualTo("GET"); // note: in practice this will become lowercase
  }

  @Test public void request_addsMethodAndPath() {
    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/foo");

    parser.request(adapter, request, customizer);

    verify(customizer).tag("http.method", "GET");
    verify(customizer).tag("http.path", "/foo");
  }

  @Test public void request_doesntCrashOnNullPath() {
    parser.request(adapter, request, customizer);

    verify(customizer, never()).tag("http.path", null);
  }

  @Test public void response_tagsStatusAndErrorOnResponseCode() {
    when(adapter.statusCodeAsInt(response)).thenReturn(400);

    parser.response(adapter, response, null, customizer);

    verify(customizer).tag("http.status_code", "400");
    verify(customizer).tag("error", "400");
  }

  @Test public void response_statusZeroIsNotAnError() {
    when(adapter.statusCodeAsInt(response)).thenReturn(0);

    parser.response(adapter, response, null, customizer);

    verify(customizer, never()).tag("http.status_code", "0");
    verify(customizer, never()).tag("error", "0");
  }

  @Test public void response_tagsErrorFromException() {
    parser.response(adapter, response, new RuntimeException("drat"), customizer);

    verify(customizer).tag("error", "drat");
  }

  @Test public void response_tagsErrorPrefersExceptionVsResponseCode() {
    when(adapter.statusCodeAsInt(response)).thenReturn(400);

    parser.response(adapter, response, new RuntimeException("drat"), customizer);

    verify(customizer).tag("error", "drat");
  }

  @Test public void response_tagsErrorOnExceptionEvenIfStatusOk() {
    when(adapter.statusCodeAsInt(response)).thenReturn(200);

    parser.response(adapter, response, new RuntimeException("drat"), customizer);

    verify(customizer).tag("error", "drat");
  }

  @Test public void routeBasedName() {
    when(adapter.methodFromResponse(response)).thenReturn("GET");
    when(adapter.route(response)).thenReturn("/users/:userId");
    when(adapter.statusCodeAsInt(response)).thenReturn(200);

    parser.response(adapter, response, null, customizer);

    verify(customizer).name("GET /users/:userId"); // zipkin will implicitly lowercase this
  }

  @Test public void routeBasedName_redirect() {
    when(adapter.methodFromResponse(response)).thenReturn("GET");
    when(adapter.route(response)).thenReturn("");
    when(adapter.statusCodeAsInt(response)).thenReturn(307);

    parser.response(adapter, response, null, customizer);

    verify(customizer).name("GET redirected"); // zipkin will implicitly lowercase this
  }

  @Test public void routeBasedName_notFound() {
    when(adapter.methodFromResponse(response)).thenReturn("DELETE");
    when(adapter.route(response)).thenReturn("");
    when(adapter.statusCodeAsInt(response)).thenReturn(404);

    parser.response(adapter, response, null, customizer);

    verify(customizer).name("DELETE not_found"); // zipkin will implicitly lowercase this
  }

  @Test public void routeBasedName_skipsOnMissingData() {
    when(adapter.methodFromResponse(response)).thenReturn("DELETE");
    when(adapter.route(response)).thenReturn(null); // missing!
    when(adapter.statusCodeAsInt(response)).thenReturn(404);

    parser.response(adapter, response, null, customizer);

    verify(customizer, never()).name(any(String.class));
  }
}
