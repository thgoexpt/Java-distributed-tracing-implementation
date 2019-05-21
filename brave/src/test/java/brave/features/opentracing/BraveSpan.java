/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package brave.features.opentracing;

import brave.propagation.ExtraFieldPropagation;
import io.opentracing.SpanContext;
import java.util.Map;

final class BraveSpan implements io.opentracing.Span {

  static BraveSpan wrap(brave.Span span) {
    if (span == null) throw new NullPointerException("span == null");
    return new BraveSpan(span);
  }

  final brave.Span delegate;
  final SpanContext context;

  BraveSpan(brave.Span delegate) {
    this.delegate = delegate;
    this.context = new BraveSpanContext(delegate.context());
  }

  public final brave.Span unwrap() {
    return delegate;
  }

  @Override public SpanContext context() {
    return context;
  }

  @Override public void finish() {
    delegate.finish();
  }

  @Override public void finish(long finishMicros) {
    delegate.finish(finishMicros);
  }

  @Override public io.opentracing.Span setTag(String key, String value) {
    delegate.tag(key, value);
    return this;
  }

  @Override public io.opentracing.Span setTag(String key, boolean value) {
    return setTag(key, Boolean.toString(value));
  }

  @Override public io.opentracing.Span setTag(String key, Number value) {
    return setTag(key, value.toString());
  }

  @Override public io.opentracing.Span log(Map<String, ?> fields) {
    if (fields.isEmpty()) return this;
    // in real life, do like zipkin-go-opentracing: "key1=value1 key2=value2"
    return log(fields.toString());
  }

  @Override public io.opentracing.Span log(long timestampMicroseconds, Map<String, ?> fields) {
    if (fields.isEmpty()) return this;
    // in real life, do like zipkin-go-opentracing: "key1=value1 key2=value2"
    return log(timestampMicroseconds, fields.toString());
  }

  @Override public io.opentracing.Span log(String event) {
    delegate.annotate(event);
    return this;
  }

  @Override public io.opentracing.Span log(long timestampMicroseconds, String event) {
    delegate.annotate(timestampMicroseconds, event);
    return this;
  }

  @Override public io.opentracing.Span setBaggageItem(String key, String value) {
    ExtraFieldPropagation.set(delegate.context(), key, value);
    return this;
  }

  @Override public String getBaggageItem(String key) {
    return ExtraFieldPropagation.get(delegate.context(), key);
  }

  @Override public io.opentracing.Span setOperationName(String operationName) {
    delegate.name(operationName);
    return this;
  }
}
