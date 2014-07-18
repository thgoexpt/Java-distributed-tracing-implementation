package com.github.kristofa.brave.httpclient;

import com.github.kristofa.brave.ClientTracer;
import com.github.kristofa.brave.client.ClientRequestInterceptor;
import com.google.common.base.Optional;
import org.apache.commons.lang.Validate;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.protocol.HttpContext;

/**
 * Apache HttpClient {@link HttpRequestInterceptor} that adds brave/zipkin annotations to outgoing client request.
 * <p/>
 * We assume the first part of the URI is the context path. The context name will be used as service name in endpoint.
 * Remaining part of path will be used as span name unless X-B3-SpanName http header is set. For example, if we have URI:
 * <p/>
 * <code>/service/path/a/b</code>
 * <p/>
 * The service name will be 'service'. The span name will be '/path/a/b'.
 * 
 * @author kristof
 */
public class BraveHttpRequestInterceptor implements HttpRequestInterceptor {
    private final ClientRequestInterceptor clientRequestInterceptor;
    private final Optional<String> serviceName;

    /**
     * Creates a new instance.
     * 
     * @param clientTracer ClientTracer should not be <code>null</code>.
     */
    public BraveHttpRequestInterceptor(final ClientTracer clientTracer, Optional<String> serviceName) {
        Validate.notNull(clientTracer);
        Validate.notNull(serviceName);
        this.clientRequestInterceptor = new ClientRequestInterceptor(clientTracer);
        this.serviceName = serviceName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void process(final HttpRequest request, final HttpContext context) {
        clientRequestInterceptor.handle(new ApacheRequestAdapter(request), serviceName);
    }
}
