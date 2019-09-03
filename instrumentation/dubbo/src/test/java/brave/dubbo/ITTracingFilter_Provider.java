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
package brave.dubbo;

import brave.sampler.Sampler;
import org.apache.dubbo.common.beanutil.JavaBeanDescriptor;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.Before;
import org.junit.Test;
import zipkin2.Span;

import static brave.sampler.Sampler.NEVER_SAMPLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.failBecauseExceptionWasNotThrown;

public class ITTracingFilter_Provider extends ITTracingFilter {

  @Before public void setup() throws Exception{
    server.service.setFilter("tracing");
    server.service.setRef((method, parameterTypes, args) -> {
      JavaBeanDescriptor arg = (JavaBeanDescriptor) args[0];
      if (arg.getProperty("value").equals("bad")) {
        throw new IllegalArgumentException();
      }
      String value = tracing != null && tracing.currentTraceContext().get() != null
        ? tracing.currentTraceContext().get().traceIdString()
        : "";
      arg.setProperty("value", value);
      return args[0];
    });
    setTracing(tracingBuilder(Sampler.ALWAYS_SAMPLE).build());
    server.start();

    client = new ReferenceConfig<>();
    client.setApplication(application);
    client.setInterface(GreeterService.class);
    client.setUrl("dubbo://" + server.ip() + ":" + server.port() + "?scope=remote&generic=bean");

    // perform a warmup request to allow CI to fail quicker
    client.get().sayHello("jorge");
    takeSpan();
  }

  @Test public void usesExistingTraceId() throws Exception {
    final String traceId = "463ac35c9f6413ad";
    final String parentId = traceId;
    final String spanId = "48485a3953bb6124";

    RpcContext.getContext().getAttachments().put("X-B3-TraceId", traceId);
    RpcContext.getContext().getAttachments().put("X-B3-ParentSpanId", parentId);
    RpcContext.getContext().getAttachments().put("X-B3-SpanId", spanId);
    RpcContext.getContext().getAttachments().put("X-B3-Sampled", "1");

    client.get().sayHello("jorge");

    Span span = takeSpan();
    assertThat(span.traceId()).isEqualTo(traceId);
    assertThat(span.parentId()).isEqualTo(parentId);
    assertThat(span.id()).isEqualTo(spanId);
    assertThat(span.shared()).isTrue();
  }

  @Test public void createsChildWhenJoinDisabled() throws Exception {
    setTracing(tracingBuilder(NEVER_SAMPLE).supportsJoin(false).build());

    final String traceId = "463ac35c9f6413ad";
    final String parentId = traceId;
    final String spanId = "48485a3953bb6124";

    RpcContext.getContext().getAttachments().put("X-B3-TraceId", traceId);
    RpcContext.getContext().getAttachments().put("X-B3-ParentSpanId", parentId);
    RpcContext.getContext().getAttachments().put("X-B3-SpanId", spanId);
    RpcContext.getContext().getAttachments().put("X-B3-Sampled", "1");

    client.get().sayHello("jorge");

    Span span = takeSpan();
    assertThat(span.traceId()).isEqualTo(traceId);
    assertThat(span.parentId()).isEqualTo(spanId);
    assertThat(span.id()).isNotEqualTo(spanId);
    assertThat(span.shared()).isNull();
  }

  @Test public void samplingDisabled() throws Exception {
    setTracing(tracingBuilder(NEVER_SAMPLE).build());

    client.get().sayHello("jorge");

    // @After will check that nothing is reported
  }

  @Test public void currentSpanVisibleToImpl() throws Exception {
    assertThat(client.get().sayHello("jorge"))
      .isNotEmpty();

    takeSpan();
  }

  @Test public void reportsServerKindToZipkin() throws Exception {
    client.get().sayHello("jorge");

    Span span = takeSpan();
    assertThat(span.kind())
      .isEqualTo(Span.Kind.SERVER);
  }

  @Test public void defaultSpanNameIsMethodName() throws Exception {
    client.get().sayHello("jorge");

    Span span = takeSpan();
    assertThat(span.name())
      .isEqualTo("genericservice/sayhello");
  }

  @Test public void addsErrorTagOnException() throws Exception {
    try {
      client.get().sayHello("bad");

      failBecauseExceptionWasNotThrown(IllegalArgumentException.class);
    } catch (IllegalArgumentException e) {
      Span span = takeSpan();
      assertThat(span.tags()).containsExactly(
        entry("error", "IllegalArgumentException")
      );
    }
  }
}
