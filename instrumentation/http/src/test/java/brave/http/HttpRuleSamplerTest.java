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
package brave.http;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.assertj.core.api.Java6Assertions.assertThat;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class HttpRuleSamplerTest {
  @Mock HttpClientAdapter<Object, Object> adapter;
  Object request = new Object();

  @Mock HttpClientRequest httpClientRequest;
  @Mock HttpServerRequest httpServerRequest;

  @Test public void onPath() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithProbability(null, "/foo", 1.0f)
      .build();

    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/foo");

    assertThat(sampler.trySample(adapter, request))
      .isTrue();

    when(httpClientRequest.method()).thenReturn("GET");
    when(httpClientRequest.path()).thenReturn("/foo");

    assertThat(sampler.trySample(httpClientRequest))
      .isTrue();

    when(httpServerRequest.method()).thenReturn("GET");
    when(httpServerRequest.path()).thenReturn("/foo");

    assertThat(sampler.trySample(httpServerRequest))
      .isTrue();
  }

  @Test public void onPath_rate() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithRate(null, "/foo", 1)
      .build();

    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/foo");

    assertThat(sampler.trySample(adapter, request))
      .isTrue();
  }

  @Test public void onPath_unsampled() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithProbability(null, "/foo", 0.0f)
      .build();

    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/foo");

    assertThat(sampler.trySample(adapter, request))
      .isFalse();
  }

  @Test public void onPath_unsampled_rate() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithRate(null, "/foo", 0)
      .build();

    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/foo");

    assertThat(sampler.trySample(adapter, request))
      .isFalse();

    when(httpClientRequest.method()).thenReturn("GET");
    when(httpClientRequest.path()).thenReturn("/foo");

    assertThat(sampler.trySample(httpClientRequest))
      .isFalse();

    when(httpServerRequest.method()).thenReturn("GET");
    when(httpServerRequest.path()).thenReturn("/foo");

    assertThat(sampler.trySample(httpServerRequest))
      .isFalse();
  }

  @Test public void onPath_sampled_prefix() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithProbability(null, "/foo", 0.0f)
      .build();

    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/foo/abcd");

    assertThat(sampler.trySample(adapter, request))
      .isFalse();

    when(httpClientRequest.method()).thenReturn("GET");
    when(httpClientRequest.path()).thenReturn("/foo/abcd");

    assertThat(sampler.trySample(httpClientRequest))
      .isFalse();

    when(httpServerRequest.method()).thenReturn("GET");
    when(httpServerRequest.path()).thenReturn("/foo/abcd");

    assertThat(sampler.trySample(httpServerRequest))
      .isFalse();
  }

  @Test public void onPath_doesntMatch() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithProbability(null, "/foo", 0.0f)
      .build();

    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/bar");

    assertThat(sampler.trySample(adapter, request))
      .isNull();

    when(httpClientRequest.method()).thenReturn("GET");
    when(httpClientRequest.path()).thenReturn("/bar");

    assertThat(sampler.trySample(httpClientRequest))
      .isNull();

    when(httpServerRequest.method()).thenReturn("GET");
    when(httpServerRequest.path()).thenReturn("/bar");

    assertThat(sampler.trySample(httpServerRequest))
      .isNull();
  }

  @Test public void onMethodAndPath_sampled() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithProbability("GET", "/foo", 1.0f)
      .build();

    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/foo");

    assertThat(sampler.trySample(adapter, request))
      .isTrue();

    when(httpClientRequest.method()).thenReturn("GET");
    when(httpClientRequest.path()).thenReturn("/foo");

    assertThat(sampler.trySample(httpClientRequest))
      .isTrue();

    when(httpServerRequest.method()).thenReturn("GET");
    when(httpServerRequest.path()).thenReturn("/foo");

    assertThat(sampler.trySample(httpServerRequest))
      .isTrue();
  }

  @Test public void onMethodAndPath_sampled_prefix() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithProbability("GET", "/foo", 1.0f)
      .build();

    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/foo/abcd");

    assertThat(sampler.trySample(adapter, request))
      .isTrue();

    when(httpClientRequest.method()).thenReturn("GET");
    when(httpClientRequest.path()).thenReturn("/foo/abcd");

    assertThat(sampler.trySample(httpClientRequest))
      .isTrue();

    when(httpServerRequest.method()).thenReturn("GET");
    when(httpServerRequest.path()).thenReturn("/foo/abcd");

    assertThat(sampler.trySample(httpServerRequest))
      .isTrue();
  }

  @Test public void onMethodAndPath_unsampled() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithProbability("GET", "/foo", 0.0f)
      .build();

    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/foo");

    assertThat(sampler.trySample(adapter, request))
      .isFalse();

    when(httpClientRequest.method()).thenReturn("GET");
    when(httpClientRequest.path()).thenReturn("/foo");

    assertThat(sampler.trySample(httpClientRequest))
      .isFalse();

    when(httpServerRequest.method()).thenReturn("GET");
    when(httpServerRequest.path()).thenReturn("/foo");

    assertThat(sampler.trySample(httpServerRequest))
      .isFalse();
  }

  @Test public void onMethodAndPath_doesntMatch_method() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithProbability("GET", "/foo", 0.0f)
      .build();

    when(adapter.method(request)).thenReturn("POST");
    when(adapter.path(request)).thenReturn("/foo");

    assertThat(sampler.trySample(adapter, request))
      .isNull();

    when(httpClientRequest.method()).thenReturn("POST");
    when(httpClientRequest.path()).thenReturn("/foo");

    assertThat(sampler.trySample(httpClientRequest))
      .isNull();

    when(httpServerRequest.method()).thenReturn("POST");
    when(httpServerRequest.path()).thenReturn("/foo");

    assertThat(sampler.trySample(httpServerRequest))
      .isNull();
  }

  @Test public void onMethodAndPath_doesntMatch_path() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithProbability("GET", "/foo", 0.0f)
      .build();

    when(adapter.method(request)).thenReturn("GET");
    when(adapter.path(request)).thenReturn("/bar");

    assertThat(sampler.trySample(adapter, request))
      .isNull();

    when(httpClientRequest.method()).thenReturn("GET");
    when(httpClientRequest.path()).thenReturn("/bar");

    assertThat(sampler.trySample(httpClientRequest))
      .isNull();

    when(httpServerRequest.method()).thenReturn("GET");
    when(httpServerRequest.path()).thenReturn("/bar");

    assertThat(sampler.trySample(httpServerRequest))
      .isNull();
  }

  @Test public void nullOnParseFailure() {
    HttpSampler sampler = HttpRuleSampler.newBuilder()
      .addRuleWithProbability("GET", "/foo", 0.0f)
      .build();

    // not setting up mocks means they return null which is like a parse fail
    assertThat(sampler.trySample(adapter, request))
      .isNull();
    assertThat(sampler.trySample(httpClientRequest))
      .isNull();
    assertThat(sampler.trySample(httpServerRequest))
      .isNull();
  }
}
