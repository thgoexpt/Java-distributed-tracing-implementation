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
package brave.grpc;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.propagation.TraceContext.Injector;
import brave.rpc.RpcRequest;
import brave.sampler.SamplerFunction;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.ForwardingClientCallListener.SimpleForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;

import static brave.grpc.GrpcClientRequest.SETTER;

// not exposed directly as implementation notably changes between versions 1.2 and 1.3
final class TracingClientInterceptor implements ClientInterceptor {
  final Tracer tracer;
  final SamplerFunction<RpcRequest> sampler;
  final Injector<GrpcClientRequest> injector;
  final GrpcClientParser parser;

  TracingClientInterceptor(GrpcTracing grpcTracing) {
    tracer = grpcTracing.rpcTracing.tracing().tracer();
    sampler = grpcTracing.rpcTracing.clientSampler();
    injector = grpcTracing.propagation.injector(SETTER);
    parser = grpcTracing.clientParser;
  }

  /**
   * This sets as span in scope both for the interception and for the start of the request. It does
   * not set a span in scope during the response listener as it is unexpected it would be used at
   * that fine granularity. If users want access to the span in a response listener, they will need
   * to wrap the executor with one that's aware of the current context.
   */
  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method,
    CallOptions callOptions, Channel next) {
    GrpcClientRequest request = new GrpcClientRequest(method);
    Span span = tracer.nextSpan(sampler, request);

    SpanInScope scope = tracer.withSpanInScope(span);
    Throwable error = null;
    try {
      return new SimpleForwardingClientCall<ReqT, RespT>(next.newCall(method, callOptions)) {
        @Override public void start(Listener<RespT> responseListener, Metadata headers) {
          request.metadata = headers;
          injector.inject(span.context(), request);
          span.kind(Span.Kind.CLIENT).start();
          SpanInScope scope = tracer.withSpanInScope(span);
          try { // retrolambda can't resolve this try/finally
            parser.onStart(method, callOptions, headers, span.customizer());
            super.start(new TracingClientCallListener<>(responseListener, span), headers);
          } finally {
            scope.close();
          }
        }

        @Override public void sendMessage(ReqT message) {
          SpanInScope scope = tracer.withSpanInScope(span);
          try { // retrolambda can't resolve this try/finally
            super.sendMessage(message);
            parser.onMessageSent(message, span.customizer());
          } finally {
            scope.close();
          }
        }
      };
    } catch (RuntimeException | Error e) {
      error = e;
      throw e;
    } finally {
      if (error != null) span.error(error).finish();
      scope.close();
    }
  }

  final class TracingClientCallListener<RespT> extends SimpleForwardingClientCallListener<RespT> {
    final Span span;

    TracingClientCallListener(ClientCall.Listener<RespT> responseListener, Span span) {
      super(responseListener);
      this.span = span;
    }

    @Override public void onMessage(RespT message) {
      SpanInScope scope = tracer.withSpanInScope(span);
      try { // retrolambda can't resolve this try/finally
        parser.onMessageReceived(message, span.customizer());
        delegate().onMessage(message);
      } finally {
        scope.close();
      }
    }

    @Override public void onClose(Status status, Metadata trailers) {
      SpanInScope scope = tracer.withSpanInScope(span);
      try {
        super.onClose(status, trailers);
        parser.onClose(status, trailers, span.customizer());
      } finally {
        span.finish();
        scope.close();
      }
    }
  }
}
