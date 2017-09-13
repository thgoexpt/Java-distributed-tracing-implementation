package brave.p6spy;

import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.propagation.StrictCurrentTraceContext;
import brave.sampler.Sampler;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import zipkin2.Span;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class ITTracingP6Factory {
  static final String URL = "jdbc:p6spy:derby:memory:p6spy;create=true";
  static final String QUERY = "SELECT 1 FROM SYSIBM.SYSDUMMY1";

  //Get rid of annoying derby.log
  static {
    DerbyUtils.disableLog();
  }

  ConcurrentLinkedDeque<Span> spans = new ConcurrentLinkedDeque<>();

  Tracing tracing = tracingBuilder(Sampler.ALWAYS_SAMPLE, spans).build();
  Connection connection;

  @Before
  public void setup() throws Exception {
    DriverManager.getDriver(URL);
    connection = DriverManager.getConnection(URL, "foo", "bar");
  }

  @After
  public void close() throws Exception {
    Tracing.current().close();
    connection.close();
  }

  @Test
  public void makesChildOfCurrentSpan() throws Exception {
    brave.Span parent = tracing.tracer().newTrace().name("test").start();
    try (SpanInScope ws = tracing.tracer().withSpanInScope(parent)) {
      prepareExecuteSelect(QUERY);
    } finally {
      parent.finish();
    }

    assertThat(spans)
        .hasSize(2);
  }

  @Test
  public void reportsClientKindToZipkin() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(spans)
        .flatExtracting(Span::kind)
        .containsExactly(Span.Kind.CLIENT);
  }

  @Test
  public void defaultSpanNameIsOperationName() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(spans)
        .extracting(Span::name)
        .containsExactly("select");
  }

  @Test
  public void addsQueryTag() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(spans)
        .flatExtracting(s -> s.tags().entrySet())
        .containsExactly(entry("sql.query", QUERY));
  }

  @Test
  public void reportsServerAddress() throws Exception {
    prepareExecuteSelect(QUERY);

    assertThat(spans)
        .flatExtracting(Span::remoteServiceName)
        .containsExactly("myservice");
  }

  void prepareExecuteSelect(String query) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(query)) {
      try (ResultSet resultSet = ps.executeQuery()) {
        while (resultSet.next()) {
          resultSet.getString(1);
        }
      }
    }
  }

  static Tracing.Builder tracingBuilder(Sampler sampler, ConcurrentLinkedDeque<Span> spans) {
    return Tracing.newBuilder()
        .spanReporter(spans::add)
        .currentTraceContext(new StrictCurrentTraceContext())
        .sampler(sampler);
  }
}
