package brave.context.log4j12;

import brave.internal.HexCodec;
import brave.internal.Nullable;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContextTest;
import brave.propagation.TraceContext;
import org.apache.log4j.MDC;
import org.junit.ComparisonFailure;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class MDCCurrentTraceContextTest extends CurrentTraceContextTest {

  @Override protected CurrentTraceContext newCurrentTraceContext() {
    return MDCCurrentTraceContext.create();
  }

  @Test public void is_inheritable() throws Exception {
    super.is_inheritable(currentTraceContext);
  }

  @Test(expected = ComparisonFailure.class) // Log4J 1.2.x MDC is inheritable by default
  public void isnt_inheritable() throws Exception {
    super.isnt_inheritable();
  }

  protected void verifyImplicitContext(@Nullable TraceContext context) {
    if (context != null) {
      assertThat(MDC.get("traceId"))
          .isEqualTo(context.traceIdString());
      assertThat(MDC.get("spanId"))
          .isEqualTo(HexCodec.toLowerHex(context.spanId()));
    } else {
      assertThat(MDC.get("traceId"))
          .isNull();
      assertThat(MDC.get("spanId"))
          .isNull();
    }
  }
}

