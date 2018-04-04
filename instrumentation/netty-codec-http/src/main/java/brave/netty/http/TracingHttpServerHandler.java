package brave.netty.http;

import brave.Span;
import brave.Tracer;
import brave.Tracer.SpanInScope;
import brave.http.HttpSampler;
import brave.http.HttpServerHandler;
import brave.http.HttpServerParser;
import brave.http.HttpTracing;
import brave.propagation.Propagation.Getter;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.net.InetSocketAddress;
import zipkin2.Endpoint;

final class TracingHttpServerHandler extends ChannelDuplexHandler {
  static final Getter<HttpHeaders, String> GETTER = new Getter<HttpHeaders, String>() {
    @Override public String get(HttpHeaders carrier, String key) {
      return carrier.get(key);
    }

    @Override public String toString() {
      return "HttpHeaders::get";
    }
  };

  final Tracer tracer;
  final HttpNettyAdapter adapter;
  final TraceContext.Extractor<HttpHeaders> extractor;
  final HttpSampler sampler;
  final HttpServerParser parser;

  TracingHttpServerHandler(HttpTracing httpTracing) {
    tracer = httpTracing.tracing().tracer();
    sampler = httpTracing.serverSampler();
    parser = httpTracing.serverParser();
    adapter = new HttpNettyAdapter();
    extractor = httpTracing.tracing().propagation().extractor(GETTER);
  }

  @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (!(msg instanceof HttpRequest)) {
      ctx.fireChannelRead(msg); // superclass does not throw
      return;
    }

    HttpRequest request = (HttpRequest) msg;

    Span span = nextSpan(extractor.extract(request.headers()), request).kind(Span.Kind.SERVER);
    ctx.channel().attr(NettyHttpTracing.SPAN_ATTRIBUTE).set(span);
    SpanInScope spanInScope = tracer.withSpanInScope(span);
    ctx.channel().attr(NettyHttpTracing.SPAN_IN_SCOPE_ATTRIBUTE).set(spanInScope);

    // Place the span in scope so that downstream code can read trace IDs
    try {
      if (!span.isNoop()) {
        parser.request(adapter, request, span.customizer());
        maybeParseClientAddress(ctx.channel(), request, span);
        span.start();
      }
      ctx.fireChannelRead(msg);
      spanInScope.close();
    } catch (RuntimeException | Error e) {
      spanInScope.close();
      span.error(e).finish(); // the request abended, so finish the span;
      throw e;
    }
  }

  /** Like {@link HttpServerHandler}, but accepts a channel */
  void maybeParseClientAddress(Channel channel, HttpRequest request, Span span) {
    Endpoint.Builder remoteEndpoint = Endpoint.newBuilder();
    if (adapter.parseClientAddress(request, remoteEndpoint)) {
      span.remoteEndpoint(remoteEndpoint.build());
    } else {
      InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
      span.remoteEndpoint(Endpoint.newBuilder()
          .ip(remoteAddress.getAddress())
          .port(remoteAddress.getPort())
          .build());
    }
  }

  /** Creates a potentially noop span representing this request */
  // copy/pasted from HttpServerHandler.nextSpan
  Span nextSpan(TraceContextOrSamplingFlags extracted, HttpRequest request) {
    if (extracted.sampled() == null) { // Otherwise, try to make a new decision
      extracted = extracted.sampled(sampler.trySample(adapter, request));
    }
    return extracted.context() != null
        ? tracer.joinSpan(extracted.context())
        : tracer.nextSpan(extracted);
  }

  @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise prm) {
    Span span = ctx.channel().attr(NettyHttpTracing.SPAN_ATTRIBUTE).get();
    if (span == null || !(msg instanceof HttpResponse)) {
      ctx.write(msg, prm);
      return;
    }

    HttpResponse response = (HttpResponse) msg;

    // Guard re-scoping the same span
    SpanInScope spanInScope = ctx.channel().attr(NettyHttpTracing.SPAN_IN_SCOPE_ATTRIBUTE).get();
    if (spanInScope == null) spanInScope = tracer.withSpanInScope(span);
    try {
      ctx.write(msg, prm);
      parser.response(adapter, response, null, span.customizer());
    } catch (RuntimeException | Error e) {
      span.error(e);
      throw e;
    } finally {
      spanInScope.close(); // clear scope before reporting
      span.finish();
    }
  }
}
