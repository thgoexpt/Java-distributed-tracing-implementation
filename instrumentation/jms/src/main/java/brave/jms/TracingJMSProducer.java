package brave.jms;

import brave.Span;
import brave.Tracer.SpanInScope;
import brave.propagation.Propagation.Getter;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import javax.jms.CompletionListener;
import javax.jms.Destination;
import javax.jms.JMSProducer;
import javax.jms.Message;

import static brave.propagation.B3SingleFormat.writeB3SingleFormatWithoutParentId;

@JMS2_0 final class TracingJMSProducer extends TracingProducer<JMSProducer, JMSProducer>
    implements JMSProducer {

  static final Getter<JMSProducer, String> GETTER = new Getter<JMSProducer, String>() {
    @Override public String get(JMSProducer carrier, String key) {
      return carrier.getStringProperty(key);
    }

    @Override public String toString() {
      return "JMSProducer::getStringProperty";
    }
  };

  final Extractor<JMSProducer> extractor;

  TracingJMSProducer(JMSProducer delegate, JmsTracing jmsTracing) {
    super(delegate, jmsTracing);
    this.extractor = jmsTracing.tracing.propagation().extractor(GETTER);
  }

  @Override void addB3SingleHeader(JMSProducer message, TraceContext context) {
    message.setProperty("b3", writeB3SingleFormatWithoutParentId(context));
  }

  @Override void clearPropagationHeaders(JMSProducer message) {
    PropertyFilter.JMS_PRODUCER.filterProperties(message, jmsTracing.propagationKeys);
  }

  @Override TraceContextOrSamplingFlags extractAndClearMessage(JMSProducer message) {
    TraceContextOrSamplingFlags extracted = extractor.extract(message);
    PropertyFilter.JMS_PRODUCER.filterProperties(message, jmsTracing.propagationKeys);
    return extracted;
  }

  @Override Destination destination(JMSProducer producer) {
    return null; // there's no implicit destination
  }

  // Partial function pattern as this needs to work before java 8 method references
  enum Send {
    MESSAGE {
      @Override void apply(JMSProducer producer, Destination destination, Object message) {
        producer.send(destination, (Message) message);
      }
    },
    STRING {
      @Override void apply(JMSProducer producer, Destination destination, Object message) {
        producer.send(destination, (String) message);
      }
    },
    MAP {
      @Override void apply(JMSProducer producer, Destination destination, Object message) {
        producer.send(destination, (Map<String, Object>) message);
      }
    },
    BYTES {
      @Override void apply(JMSProducer producer, Destination destination, Object message) {
        producer.send(destination, (byte[]) message);
      }
    },
    SERIALIZABLE {
      @Override void apply(JMSProducer producer, Destination destination, Object message) {
        producer.send(destination, (Serializable) message);
      }
    };

    abstract void apply(JMSProducer producer, Destination destination, Object message);
  }

  @Override public JMSProducer send(Destination destination, Message message) {
    send(Send.MESSAGE, destination, message);
    return this;
  }

  @Override public JMSProducer send(Destination destination, String body) {
    send(Send.STRING, destination, body);
    return this;
  }

  @Override public JMSProducer send(Destination destination, Map<String, Object> body) {
    send(Send.MAP, destination, body);
    return this;
  }

  @Override public JMSProducer send(Destination destination, byte[] body) {
    send(Send.BYTES, destination, body);
    return this;
  }

  @Override public JMSProducer send(Destination destination, Serializable body) {
    send(Send.SERIALIZABLE, destination, body);
    return this;
  }

  void send(Send send, Destination destination, Object message) {
    Span span = createAndStartProducerSpan(destination, this);
    final CompletionListener oldCompletionListener = getAsync();
    if (oldCompletionListener != null) {
      delegate.setAsync(TracingCompletionListener.create(oldCompletionListener, span, current));
    }
    SpanInScope ws = tracer.withSpanInScope(span); // animal-sniffer mistakes this for AutoCloseable
    try {
      send.apply(delegate, destination, message);
    } catch (RuntimeException | Error e) {
      span.error(e);
      span.finish();
      throw e;
    } finally {
      ws.close();
      if (oldCompletionListener != null) {
        delegate.setAsync(oldCompletionListener);
      } else {
        span.finish();
      }
    }
  }

  @Override public JMSProducer setDisableMessageID(boolean value) {
    delegate.setDisableMessageID(value);
    return this;
  }

  @Override public boolean getDisableMessageID() {
    return delegate.getDisableMessageID();
  }

  @Override public JMSProducer setDisableMessageTimestamp(boolean value) {
    delegate.setDisableMessageTimestamp(value);
    return this;
  }

  @Override public boolean getDisableMessageTimestamp() {
    return delegate.getDisableMessageTimestamp();
  }

  @Override public JMSProducer setDeliveryMode(int deliveryMode) {
    delegate.setDeliveryMode(deliveryMode);
    return this;
  }

  @Override public int getDeliveryMode() {
    return delegate.getDeliveryMode();
  }

  @Override public JMSProducer setPriority(int priority) {
    delegate.setPriority(priority);
    return this;
  }

  @Override public int getPriority() {
    return delegate.getPriority();
  }

  @Override public JMSProducer setTimeToLive(long timeToLive) {
    delegate.setTimeToLive(timeToLive);
    return this;
  }

  @Override public long getTimeToLive() {
    return delegate.getTimeToLive();
  }

  @Override public JMSProducer setDeliveryDelay(long deliveryDelay) {
    delegate.setDeliveryDelay(deliveryDelay);
    return this;
  }

  @Override public long getDeliveryDelay() {
    return delegate.getDeliveryDelay();
  }

  @Override public JMSProducer setAsync(CompletionListener completionListener) {
    delegate.setAsync(completionListener);
    return this;
  }

  @Override public CompletionListener getAsync() {
    return delegate.getAsync();
  }

  @Override public JMSProducer setProperty(String name, boolean value) {
    delegate.setProperty(name, value);
    return this;
  }

  @Override public JMSProducer setProperty(String name, byte value) {
    delegate.setProperty(name, value);
    return this;
  }

  @Override public JMSProducer setProperty(String name, short value) {
    delegate.setProperty(name, value);
    return this;
  }

  @Override public JMSProducer setProperty(String name, int value) {
    delegate.setProperty(name, value);
    return this;
  }

  @Override public JMSProducer setProperty(String name, long value) {
    delegate.setProperty(name, value);
    return this;
  }

  @Override public JMSProducer setProperty(String name, float value) {
    delegate.setProperty(name, value);
    return this;
  }

  @Override public JMSProducer setProperty(String name, double value) {
    delegate.setProperty(name, value);
    return this;
  }

  @Override public JMSProducer setProperty(String name, String value) {
    delegate.setProperty(name, value);
    return this;
  }

  @Override public JMSProducer setProperty(String name, Object value) {
    delegate.setProperty(name, value);
    return this;
  }

  @Override public JMSProducer clearProperties() {
    delegate.clearProperties();
    return this;
  }

  @Override public boolean propertyExists(String name) {
    return delegate.propertyExists(name);
  }

  @Override public boolean getBooleanProperty(String name) {
    return delegate.getBooleanProperty(name);
  }

  @Override public byte getByteProperty(String name) {
    return delegate.getByteProperty(name);
  }

  @Override public short getShortProperty(String name) {
    return delegate.getShortProperty(name);
  }

  @Override public int getIntProperty(String name) {
    return delegate.getIntProperty(name);
  }

  @Override public long getLongProperty(String name) {
    return delegate.getLongProperty(name);
  }

  @Override public float getFloatProperty(String name) {
    return delegate.getFloatProperty(name);
  }

  @Override public double getDoubleProperty(String name) {
    return delegate.getDoubleProperty(name);
  }

  @Override public String getStringProperty(String name) {
    return delegate.getStringProperty(name);
  }

  @Override public Object getObjectProperty(String name) {
    return delegate.getObjectProperty(name);
  }

  @Override public Set<String> getPropertyNames() {
    return delegate.getPropertyNames();
  }

  @Override public JMSProducer setJMSCorrelationIDAsBytes(byte[] correlationID) {
    delegate.setJMSCorrelationIDAsBytes(correlationID);
    return this;
  }

  @Override public byte[] getJMSCorrelationIDAsBytes() {
    return delegate.getJMSCorrelationIDAsBytes();
  }

  @Override public JMSProducer setJMSCorrelationID(String correlationID) {
    delegate.setJMSCorrelationID(correlationID);
    return this;
  }

  @Override public String getJMSCorrelationID() {
    return delegate.getJMSCorrelationID();
  }

  @Override public JMSProducer setJMSType(String type) {
    delegate.setJMSType(type);
    return this;
  }

  @Override public String getJMSType() {
    return delegate.getJMSType();
  }

  @Override public JMSProducer setJMSReplyTo(Destination replyTo) {
    delegate.setJMSReplyTo(replyTo);
    return this;
  }

  @Override public Destination getJMSReplyTo() {
    return delegate.getJMSReplyTo();
  }
}
