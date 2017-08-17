package com.github.kristofa.brave;

import brave.Tracing;
import org.junit.After;

public class Brave4ClientTracerTest extends ClientTracerTest {
  @Override Brave newBrave() {
    return TracerAdapter.newBrave(Tracing.newBuilder()
        .clock(new AnnotationSubmitter.DefaultClock()::currentTimeMicroseconds)
        .localEndpoint(ZIPKIN_ENDPOINT)
        .clock(clock::currentTimeMicroseconds)
        .reporter(spans::add).build().tracer());
  }

  @After public void close(){
    Tracing.current().close();
  }
}
