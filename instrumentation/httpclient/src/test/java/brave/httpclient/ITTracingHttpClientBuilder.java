package brave.httpclient;

import brave.test.http.ITHttpClient;
import java.io.IOException;
import java.net.URI;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.Test;

import static org.apache.http.util.EntityUtils.consume;
import static org.assertj.core.api.Assertions.assertThat;

public class ITTracingHttpClientBuilder extends ITHttpClient<CloseableHttpClient> {

  @Override protected CloseableHttpClient newClient(int port) {
    return TracingHttpClientBuilder.create(httpTracing).disableAutomaticRetries().build();
  }

  @Override protected void closeClient(CloseableHttpClient client) throws IOException {
    client.close();
  }

  @Override protected void get(CloseableHttpClient client, String pathIncludingQuery)
      throws IOException {
    consume(client.execute(new HttpGet(URI.create(url(pathIncludingQuery)))).getEntity());
  }

  @Override protected void post(CloseableHttpClient client, String pathIncludingQuery, String body)
      throws Exception {
    HttpPost post = new HttpPost(URI.create(url(pathIncludingQuery)));
    post.setEntity(new StringEntity(body));
    consume(client.execute(post).getEntity());
  }

  @Test public void currentSpanVisibleToUserFilters() throws Exception {
    server.enqueue(new MockResponse());
    closeClient(client);

    client = TracingHttpClientBuilder.create(httpTracing).disableAutomaticRetries()
        .addInterceptorFirst((HttpRequestInterceptor) (request, context) ->
            request.setHeader("my-id", currentTraceContext.get().traceIdString())
        ).build();

    get(client, "/foo");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getHeader("x-b3-traceId"))
        .isEqualTo(request.getHeader("my-id"));

    takeSpan();
  }
}
