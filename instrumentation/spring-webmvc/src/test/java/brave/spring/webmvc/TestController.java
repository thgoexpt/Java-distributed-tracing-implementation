package brave.spring.webmvc;

import brave.Tracer;
import brave.http.HttpTracing;
import brave.propagation.ExtraFieldPropagation;
import brave.test.http.ITHttp;
import java.io.IOException;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

@Controller class TestController {
  final Tracer tracer;

  @Autowired TestController(HttpTracing httpTracing) {
    this.tracer = httpTracing.tracing().tracer();
  }

  @RequestMapping(method = RequestMethod.OPTIONS, value = "/")
  public ResponseEntity<Void> root() {
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @RequestMapping(value = "/foo")
  public ResponseEntity<Void> foo() {
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @RequestMapping(value = "/extra")
  public ResponseEntity<String> extra() {
    return new ResponseEntity<>(ExtraFieldPropagation.get(ITHttp.EXTRA_KEY), HttpStatus.OK);
  }

  @RequestMapping(value = "/badrequest")
  public ResponseEntity<Void> badrequest() {
    return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
  }

  @RequestMapping(value = "/child")
  public ResponseEntity<Void> child() {
    tracer.nextSpan().name("child").start().finish();
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @RequestMapping(value = "/async")
  public Callable<ResponseEntity<Void>> async() {
    return () -> new ResponseEntity<>(HttpStatus.OK);
  }

  @RequestMapping(value = "/exception")
  public ResponseEntity<Void> disconnect() throws IOException {
    throw new IOException();
  }

  @RequestMapping(value = "/exceptionAsync")
  public Callable<ResponseEntity<Void>> disconnectAsync() {
    return () -> {
      throw new IOException();
    };
  }

  @RequestMapping(value = "/items/{itemId}")
  public ResponseEntity<String> items(@PathVariable("itemId") String itemId) {
    return new ResponseEntity<String>(itemId, HttpStatus.OK);
  }

  @RequestMapping(value = "/async_items/{itemId}")
  public Callable<ResponseEntity<String>> asyncItems(@PathVariable("itemId") String itemId) {
    return () -> new ResponseEntity<String>(itemId, HttpStatus.OK);
  }

  @Controller
  @RequestMapping(value = "/nested")
  static class NestedController {
    @RequestMapping(value = "/items/{itemId}")
    public ResponseEntity<String> items(@PathVariable("itemId") String itemId) {
      return new ResponseEntity<String>(itemId, HttpStatus.OK);
    }
  }
}
