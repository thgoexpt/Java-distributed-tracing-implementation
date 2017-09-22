package com.github.kristofa.brave;

import brave.Tracing;
import org.junit.After;
import zipkin.Span;
import zipkin.reporter.Reporter;

public class Brave4ServerRequestInterceptorTest extends ServerRequestInterceptorTest {
  @Override Brave newBrave() {
    return TracerAdapter.newBrave(Tracing.newBuilder()
        .localEndpoint(ZIPKIN_ENDPOINT)
        .reporter((Reporter<Span>) spans::add).build().tracer());
  }

  @After public void close(){
    Tracing.current().close();
  }
}
