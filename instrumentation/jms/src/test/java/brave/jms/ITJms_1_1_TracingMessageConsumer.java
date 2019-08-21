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
package brave.jms;

import java.util.Collections;
import java.util.Map;
import javax.jms.BytesMessage;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.QueueReceiver;
import javax.jms.QueueSender;
import javax.jms.QueueSession;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.TopicPublisher;
import javax.jms.TopicSession;
import javax.jms.TopicSubscriber;
import org.junit.After;
import org.junit.Before;
import org.junit.ComparisonFailure;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import zipkin2.Span;

import static brave.jms.JmsTracing.GETTER;
import static brave.jms.JmsTracing.SETTER;
import static org.assertj.core.api.Assertions.assertThat;

/** When adding tests here, also add to {@linkplain brave.jms.ITTracingJMSConsumer} */
public class ITJms_1_1_TracingMessageConsumer extends JmsTest {
  @Rule public TestName testName = new TestName();
  @Rule public JmsTestRule jms = newJmsTestRule(testName);

  Session tracedSession;
  MessageProducer messageProducer;
  MessageConsumer messageConsumer;

  QueueSession tracedQueueSession;
  QueueSender queueSender;
  QueueReceiver queueReceiver;

  TopicSession tracedTopicSession;
  TopicPublisher topicPublisher;
  TopicSubscriber topicSubscriber;

  JmsTestRule newJmsTestRule(TestName testName) {
    return new JmsTestRule.ActiveMQ(testName);
  }

  TextMessage message;
  BytesMessage bytesMessage;

  @Before public void setup() throws Exception {
    tracedSession = jmsTracing.connection(jms.connection)
      .createSession(false, Session.AUTO_ACKNOWLEDGE);
    tracedQueueSession = jmsTracing.queueConnection(jms.queueConnection)
      .createQueueSession(false, Session.AUTO_ACKNOWLEDGE);
    tracedTopicSession = jmsTracing.topicConnection(jms.topicConnection)
      .createTopicSession(false, Session.AUTO_ACKNOWLEDGE);

    messageProducer = jms.session.createProducer(jms.destination);
    messageConsumer = tracedSession.createConsumer(jms.destination);

    queueSender = jms.queueSession.createSender(jms.queue);
    queueReceiver = tracedQueueSession.createReceiver(jms.queue);

    topicPublisher = jms.topicSession.createPublisher(jms.topic);
    topicSubscriber = tracedTopicSession.createSubscriber(jms.topic);

    message = jms.newMessage("foo");
    bytesMessage = jms.newBytesMessage("foo");
    lockMessages();
  }

  void lockMessages() throws Exception {
    // this forces us to handle JMS write concerns!
    jms.setReadOnlyProperties(message, true);
    jms.setReadOnlyProperties(bytesMessage, true);
    bytesMessage.reset();
  }

  String resetB3PropertyToIncludeParentId(JmsTestRule jms) throws Exception {
    message = jms.newMessage("foo");
    bytesMessage = jms.newBytesMessage("foo");
    String parentId = "463ac35c9f6413ad";
    SETTER.put(message, "b3", parentId + "-" + parentId + "-1");
    SETTER.put(bytesMessage, "b3", parentId + "-" + parentId + "-1");
    lockMessages();
    return parentId;
  }

  @After public void tearDownTraced() throws JMSException {
    tracedSession.close();
    tracedQueueSession.close();
    tracedTopicSession.close();
  }

  @Test public void messageListener_startsNewTrace() throws Exception {
    messageListener_startsNewTrace(
      () -> messageProducer.send(message),
      messageConsumer,
      Collections.singletonMap("jms.queue", jms.destinationName)
    );
  }

  @Test public void messageListener_startsNewTrace_bytes() throws Exception {
    messageListener_startsNewTrace(
      () -> messageProducer.send(bytesMessage),
      messageConsumer,
      Collections.singletonMap("jms.queue", jms.destinationName)
    );
  }

  @Test public void messageListener_startsNewTrace_queue() throws Exception {
    messageListener_startsNewTrace(
      () -> queueSender.send(message),
      queueReceiver,
      Collections.singletonMap("jms.queue", jms.queueName)
    );
  }

  @Test public void messageListener_startsNewTrace_topic() throws Exception {
    messageListener_startsNewTrace(
      () -> topicPublisher.send(message),
      topicSubscriber,
      Collections.singletonMap("jms.topic", jms.topicName)
    );
  }

  void messageListener_startsNewTrace(JMSRunnable send, MessageConsumer messageConsumer,
    Map<String, String> consumerTags) throws Exception {
    messageConsumer.setMessageListener(
      m -> {
        tracing.tracer().currentSpanCustomizer().name("message-listener");

        // clearing headers ensures later work doesn't try to use the old parent
        String b3 = GETTER.get(m, "b3");
        tracing.tracer().currentSpanCustomizer().tag("b3", String.valueOf(b3 != null));
      }
    );
    send.run();

    Span consumerSpan = takeSpan(), listenerSpan = takeSpan();

    assertThat(consumerSpan.name()).isEqualTo("receive");
    assertThat(consumerSpan.parentId()).isNull(); // root span
    assertThat(consumerSpan.kind()).isEqualTo(Span.Kind.CONSUMER);
    assertThat(consumerSpan.tags()).isEqualTo(consumerTags);

    assertThat(listenerSpan.name()).isEqualTo("message-listener"); // overridden name
    assertThat(listenerSpan.parentId()).isEqualTo(consumerSpan.id()); // root span
    assertThat(listenerSpan.kind()).isNull(); // processor span, not a consumer
    assertThat(listenerSpan.tags())
      .hasSize(1) // no redundant copy of consumer tags
      .containsEntry("b3", "false"); // b3 header not leaked to listener
  }

  @Test public void messageListener_resumesTrace() throws Exception {
    messageListener_resumesTrace(() -> messageProducer.send(message), messageConsumer);
  }

  @Test public void messageListener_resumesTrace_bytes() throws Exception {
    messageListener_resumesTrace(() -> messageProducer.send(bytesMessage), messageConsumer);
  }

  @Test public void messageListener_resumesTrace_queue() throws Exception {
    messageListener_resumesTrace(() -> queueSender.send(message), queueReceiver);
  }

  @Test public void messageListener_resumesTrace_topic() throws Exception {
    messageListener_resumesTrace(() -> topicPublisher.send(message), topicSubscriber);
  }

  void messageListener_resumesTrace(JMSRunnable send, MessageConsumer messageConsumer)
    throws Exception {
    messageConsumer.setMessageListener(m -> {
        // clearing headers ensures later work doesn't try to use the old parent
        String b3 = GETTER.get(m, "b3");
        tracing.tracer().currentSpanCustomizer().tag("b3", String.valueOf(b3 != null));
      }
    );

    String parentId = resetB3PropertyToIncludeParentId(jms);
    send.run();

    Span consumerSpan = takeSpan(), listenerSpan = takeSpan();
    assertThat(consumerSpan.parentId()).isEqualTo(parentId);
    assertThat(listenerSpan.parentId()).isEqualTo(consumerSpan.id());
    assertThat(listenerSpan.tags())
      .hasSize(1) // no redundant copy of consumer tags
      .containsEntry("b3", "false"); // b3 header not leaked to listener
  }

  @Test public void receive_startsNewTrace() throws Exception {
    receive_startsNewTrace(
      () -> messageProducer.send(message),
      messageConsumer,
      Collections.singletonMap("jms.queue", jms.destinationName)
    );
  }

  @Test public void receive_startsNewTrace_queue() throws Exception {
    receive_startsNewTrace(
      () -> queueSender.send(message),
      queueReceiver,
      Collections.singletonMap("jms.queue", jms.queueName)
    );
  }

  @Test public void receive_startsNewTrace_topic() throws Exception {
    receive_startsNewTrace(
      () -> topicPublisher.send(message),
      topicSubscriber,
      Collections.singletonMap("jms.topic", jms.topicName)
    );
  }

  void receive_startsNewTrace(JMSRunnable send, MessageConsumer messageConsumer,
    Map<String, String> consumerTags) throws Exception {
    send.run();

    messageConsumer.receive();

    Span consumerSpan = takeSpan();
    assertThat(consumerSpan.name()).isEqualTo("receive");
    assertThat(consumerSpan.parentId()).isNull(); // root span
    assertThat(consumerSpan.kind()).isEqualTo(Span.Kind.CONSUMER);
    assertThat(consumerSpan.tags()).isEqualTo(consumerTags);
  }

  @Test public void receive_resumesTrace() throws Exception {
    receive_resumesTrace(() -> messageProducer.send(message), messageConsumer);
  }

  @Test(expected = ComparisonFailure.class) // TODO: https://github.com/openzipkin/brave/issues/967
  public void receive_resumesTrace_bytes() throws Exception {
    receive_resumesTrace(() -> messageProducer.send(bytesMessage), messageConsumer);
  }

  @Test public void receive_resumesTrace_queue() throws Exception {
    receive_resumesTrace(() -> queueSender.send(message), queueReceiver);
  }

  @Test public void receive_resumesTrace_topic() throws Exception {
    receive_resumesTrace(() -> topicPublisher.send(message), topicSubscriber);
  }

  void receive_resumesTrace(JMSRunnable send, MessageConsumer messageConsumer) throws Exception {
    String parentId = resetB3PropertyToIncludeParentId(jms);
    send.run();

    Message received = messageConsumer.receive();
    Span consumerSpan = takeSpan();
    assertThat(consumerSpan.parentId()).isEqualTo(parentId);

    assertThat(received.getStringProperty("b3"))
      .isEqualTo(parentId + "-" + consumerSpan.id() + "-1");
  }
}
