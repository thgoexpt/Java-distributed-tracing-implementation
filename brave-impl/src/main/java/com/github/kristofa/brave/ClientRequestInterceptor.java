package com.github.kristofa.brave;


import java.util.Optional;

/**
 * Contains logic for handling an outgoing client request.
 */
public class ClientRequestInterceptor {

    private final static String CLIENT_REQUEST_ANNOTATION_NAME = "request";

    private final ClientTracer clientTracer;

    public ClientRequestInterceptor(ClientTracer clientTracer) {
        this.clientTracer = clientTracer;
    }

    /**
     * Handles outgoing request.
     *
     * @param adapter The adapter deals with implementation specific details.
     */
    public void handle(ClientRequestAdapter adapter) {

        SpanId spanId = clientTracer.startNewSpan(adapter.getSpanName());
        if (spanId == null) {
            // We will not trace this request.
            adapter.addSpanIdToRequest(Optional.empty());
        } else {
            adapter.addSpanIdToRequest(Optional.of(spanId));
            clientTracer.setCurrentClientServiceName(adapter.getClientServiceName());
            if (adapter.getRequestRepresentation().isPresent()) {
                clientTracer.submitBinaryAnnotation(CLIENT_REQUEST_ANNOTATION_NAME, adapter.getRequestRepresentation().get());
            }
            clientTracer.setClientSent();
        }

    }

}
