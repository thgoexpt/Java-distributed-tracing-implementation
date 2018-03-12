package brave.test.http;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.bio.SocketConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;

/** Starts a jetty server which runs a servlet container */
public abstract class ServletContainer {
  int port = 0; // initially get a port, later reuse one
  Server server;

  /** recreates the server so that it uses the supplied trace configuration */
  public final void init() {
    stop();
    server = newServer(port);

    ServletContextHandler context = new ServletContextHandler();
    context.setContextPath("/");
    server.setHandler(context);

    init(context);

    try {
      server.start();
      port = getLocalPort(server);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to start server.", e);
    }
  }

  protected int getLocalPort(Server server) {
    return server.getConnectors()[0].getLocalPort();
  }

  protected Server newServer(int port) {
    Server result = new Server();
    SocketConnector connector = new SocketConnector();
    connector.setMaxIdleTime(1000 * 60 * 60);
    connector.setPort(port);
    result.setConnectors(new Connector[] {connector});
    return result;
  }

  public final String url(String path) {
    return "http://localhost:" + port + path;
  }

  /** Implement by registering a servlet for the test resource and anything needed for tracing */
  public abstract void init(ServletContextHandler handler);

  public void stop() {
    if (server == null) return;
    try {
      server.stop();
      server.join();
      server = null;
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
