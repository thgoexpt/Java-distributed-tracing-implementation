package brave.dubbo.rpc;

import brave.Span;
import brave.Span.Kind;
import brave.SpanCustomizer;
import brave.Tracer;
import brave.Tracing;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.Activate;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.remoting.exchange.ResponseCallback;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.protocol.dubbo.FutureAdapter;
import com.alibaba.dubbo.rpc.support.RpcUtils;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Future;
import zipkin2.Endpoint;

@Activate(group = {Constants.PROVIDER, Constants.CONSUMER}, value = "tracing")
// http://dubbo.io/books/dubbo-dev-book-en/impls/filter.html
// public constructor permitted to allow dubbo to instantiate this
public final class TracingFilter implements Filter {

  Tracer tracer;
  TraceContext.Extractor<Map<String, String>> extractor;
  TraceContext.Injector<Map<String, String>> injector;

  /** {@link ExtensionLoader} supplies the tracing implementation, for example by Spring. */
  public void setTracing(Tracing tracing) {
    tracer = tracing.tracer();
    extractor = tracing.propagation().extractor(GETTER);
    injector = tracing.propagation().injector(SETTER);
  }

  @Override
  public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
    if (tracer == null) return invoker.invoke(invocation);

    RpcContext rpcContext = RpcContext.getContext();
    Kind kind = rpcContext.isProviderSide() ? Kind.SERVER : Kind.CLIENT;
    final Span span;
    if (kind.equals(Kind.CLIENT)) {
      span = tracer.nextSpan();
      injector.inject(span.context(), invocation.getAttachments());
    } else {
      TraceContextOrSamplingFlags extracted = extractor.extract(invocation.getAttachments());
      span = extracted.context() != null
          ? tracer.joinSpan(extracted.context())
          : tracer.nextSpan(extracted);
    }

    if (!span.isNoop()) {
      span.kind(kind).start();
      String service = invoker.getInterface().getSimpleName();
      String method = RpcUtils.getMethodName(invocation);

      span.kind(kind);
      span.name(service + "/" + method);

      InetSocketAddress remoteAddress = rpcContext.getRemoteAddress();
      Endpoint.Builder remoteEndpoint = Endpoint.newBuilder().port(remoteAddress.getPort());
      if (!remoteEndpoint.parseIp(remoteAddress.getAddress())) {
        remoteEndpoint.parseIp(remoteAddress.getHostName());
      }
      span.remoteEndpoint(remoteEndpoint.build());
    }

    boolean isOneway = false, deferFinish = false;
    try (Tracer.SpanInScope scope = tracer.withSpanInScope(span)) {
      Result result = invoker.invoke(invocation);
      if (result.hasException()) {
        onError(result.getException(), span);
      }
      isOneway = RpcUtils.isOneway(invoker.getUrl(), invocation);
      Future<Object> future = rpcContext.getFuture(); // the case on async client invocation
      if (future instanceof FutureAdapter) {
        deferFinish = true;
        ((FutureAdapter) future).getFuture().setCallback(new FinishSpanCallback(span));
      }
      return result;
    } catch (Error | RuntimeException e) {
      onError(e, span);
      throw e;
    } finally {
      if (isOneway) {
        span.flush();
      } else if (!deferFinish) {
        span.finish();
      }
    }
  }

  static void onError(Throwable error, SpanCustomizer span) {
    String message = error.getMessage();
    if (message == null) message = error.getClass().getSimpleName();
    if (error instanceof RpcException) {
      span.tag("dubbo.error_code", Integer.toString(((RpcException) error).getCode()));
    }
    span.tag("error", message);
  }

  static final Propagation.Getter<Map<String, String>, String> GETTER =
      new Propagation.Getter<Map<String, String>, String>() {
        @Override
        public String get(Map<String, String> carrier, String key) {
          return carrier.get(key);
        }

        @Override
        public String toString() {
          return "Map::get";
        }
      };

  static final Propagation.Setter<Map<String, String>, String> SETTER =
      new Propagation.Setter<Map<String, String>, String>() {
        @Override
        public void put(Map<String, String> carrier, String key, String value) {
          carrier.put(key, value);
        }

        @Override
        public String toString() {
          return "Map::set";
        }
      };

  static final class FinishSpanCallback implements ResponseCallback {
    final Span span;

    FinishSpanCallback(Span span) {
      this.span = span;
    }

    @Override public void done(Object response) {
      span.finish();
    }

    @Override public void caught(Throwable exception) {
      onError(exception, span);
      span.finish();
    }
  }
}
