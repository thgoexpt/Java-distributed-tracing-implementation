/*
 * Copyright 2013-2020 The OpenZipkin Authors
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
package brave.internal.handler;

import brave.internal.handler.ZipkinFinishedSpanHandler.LoggingReporter;
import org.junit.Test;
import zipkin2.Span;

import static brave.test.util.ClassLoaders.assertRunIsUnloadable;

public class ZipkinFinishedSpanHandlerClassLoaderTest {
  @Test public void unloadable_withLoggingReporter() {
    assertRunIsUnloadable(UsingLoggingReporter.class, getClass().getClassLoader());
  }

  // This test will clutter output; it is somewhat difficult to avoid that and still run the test
  static class UsingLoggingReporter implements Runnable {
    @Override public void run() {
      LoggingReporter reporter = new LoggingReporter();
      reporter.report(Span.newBuilder().traceId("a").id("b").build());
    }
  }
}
