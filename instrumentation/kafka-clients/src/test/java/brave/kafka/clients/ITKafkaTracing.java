package brave.kafka.clients;

import brave.Tracing;
import brave.internal.HexCodec;
import brave.propagation.Propagation;
import brave.propagation.SamplingFlags;
import brave.propagation.TraceContext;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.propagation.TraceIdContext;
import brave.sampler.Sampler;
import com.github.charithe.kafka.EphemeralKafkaBroker;
import com.github.charithe.kafka.KafkaJunitRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import zipkin2.Span;

import static brave.kafka.clients.KafkaTags.KAFKA_TOPIC_TAG;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin.internal.Util.lowerHexToUnsignedLong;

public class ITKafkaTracing {

  String TEST_KEY = "foo";
  String TEST_VALUE = "bar";

  ConcurrentLinkedDeque<Span> consumerSpans = new ConcurrentLinkedDeque<>();
  ConcurrentLinkedDeque<Span> producerSpans = new ConcurrentLinkedDeque<>();

  KafkaTracing consumerTracing = KafkaTracing.create(Tracing.newBuilder()
      .spanReporter(consumerSpans::add)
      .build());
  KafkaTracing producerTracing = KafkaTracing.create(Tracing.newBuilder()
      .spanReporter(producerSpans::add)
      .build());

  @ClassRule
  public static KafkaJunitRule kafkaRule = new KafkaJunitRule(EphemeralKafkaBroker.create());
  @Rule
  public TestName testName = new TestName();

  Producer<String, String> producer;
  Consumer<String, String> consumer;

  @After
  public void close() throws Exception {
    if (producer != null) producer.close();
    if (consumer != null) consumer.close();
    Tracing current = Tracing.current();
    if (current != null) current.close();
  }

  @Test
  public void joinSpan_deprecated_because_it_writes_to_old_span() throws Exception {
    producer = createTracingProducer();
    consumer = createTracingConsumer();

    producer.send(new ProducerRecord<>(testName.getMethodName(), TEST_KEY, TEST_VALUE)).get();

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(1);
    assertThat(producerSpans).hasSize(1);
    assertThat(consumerSpans).hasSize(1);

    assertThat(consumerSpans.getFirst().traceId())
        .isEqualTo(producerSpans.getFirst().traceId());

    for (ConsumerRecord<String, String> record : records) {
      brave.Span joined = consumerTracing.joinSpan(record);
      joined.annotate("foo");
      joined.flush();

      // Re-using this span happens "after" it is completed, which will make the UI look strange
      // Instead, use nextSpan to create a span representing message processing.
      assertThat(consumerSpans)
          .filteredOn(s -> s.id().equals(HexCodec.toLowerHex(joined.context().spanId())))
          .hasSize(2);
    }
  }

  @Test
  public void poll_creates_one_consumer_span_per_extracted_context() throws Exception {
    String topic1 = testName.getMethodName() + "1";
    String topic2 = testName.getMethodName() + "2";

    producer = createTracingProducer();
    consumer = createTracingConsumer(topic1, topic2);

    producer.send(new ProducerRecord<>(topic1, TEST_KEY, TEST_VALUE)).get();
    producer.send(new ProducerRecord<>(topic2, TEST_KEY, TEST_VALUE)).get();

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(2);
    assertThat(producerSpans).hasSize(2);
    assertThat(consumerSpans).hasSize(2);

    // Check to see the trace is continued between the producer and the consumer
    // we don't know the order the spans will come in. Correlate with the tag instead.
    String firstTopic = producerSpans.getFirst().tags().get(KAFKA_TOPIC_TAG);
    if (firstTopic.equals(consumerSpans.getFirst().tags().get(KAFKA_TOPIC_TAG))) {
      assertThat(producerSpans.getFirst().traceId())
          .isEqualTo(consumerSpans.getFirst().traceId());
      assertThat(producerSpans.getLast().traceId())
          .isEqualTo(consumerSpans.getLast().traceId());
    } else {
      assertThat(producerSpans.getFirst().traceId())
          .isEqualTo(consumerSpans.getLast().traceId());
      assertThat(producerSpans.getLast().traceId())
          .isEqualTo(consumerSpans.getFirst().traceId());
    }
  }

  @Test
  public void poll_creates_one_consumer_span_per_topic() throws Exception {
    String topic1 = testName.getMethodName() + "1";
    String topic2 = testName.getMethodName() + "2";

    producer = kafkaRule.helper().createStringProducer(); // not traced
    consumer = createTracingConsumer(topic1, topic2);

    for (int i = 0; i < 5; i++) {
      producer.send(new ProducerRecord<>(topic1, TEST_KEY, TEST_VALUE)).get();
      producer.send(new ProducerRecord<>(topic2, TEST_KEY, TEST_VALUE)).get();
    }

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(10);
    assertThat(producerSpans).isEmpty(); // not traced
    assertThat(consumerSpans).hasSize(2); // one per topic!
  }

  @Test
  public void nextSpan_makes_child() throws Exception {
    producer = createTracingProducer();
    consumer = createTracingConsumer();

    producer.send(new ProducerRecord<>(testName.getMethodName(), TEST_KEY, TEST_VALUE)).get();

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(1);
    assertThat(producerSpans).hasSize(1);
    assertThat(consumerSpans).hasSize(1);

    for (ConsumerRecord<String, String> record : records) {
      brave.Span processor = consumerTracing.nextSpan(record);

      Span consumerSpan = consumerSpans.stream()
          .filter(s -> s.tags().get(KAFKA_TOPIC_TAG).equals(record.topic())).findAny().get();

      assertThat(processor.context().traceIdString()).isEqualTo(consumerSpan.traceId());
      assertThat(HexCodec.toLowerHex(processor.context().parentId())).isEqualTo(consumerSpan.id());

      processor.start().name("processor").finish();

      // The processor doesn't taint the consumer span which has already finished
      assertThat(consumerSpans)
          .extracting(Span::id)
          .containsOnly(HexCodec.toLowerHex(processor.context().spanId()), consumerSpan.id());
    }
  }

  static class TraceIdOnlyPropagation<K> implements Propagation<K> {
    final K key;

    TraceIdOnlyPropagation(Propagation.KeyFactory<K> keyFactory) {
      key = keyFactory.create("x-b3-traceid");
    }

    @Override public List<K> keys() {
      return Collections.singletonList(key);
    }

    @Override public <C> TraceContext.Injector<C> injector(Setter<C, K> setter) {
      return (traceContext, carrier) -> setter.put(carrier, key, traceContext.traceIdString());
    }

    @Override public <C> TraceContext.Extractor<C> extractor(Getter<C, K> getter) {
      return carrier -> {
        String result = getter.get(carrier, key);
        if (result == null) return TraceContextOrSamplingFlags.create(SamplingFlags.EMPTY);
        return TraceContextOrSamplingFlags.create(TraceIdContext.newBuilder()
            .traceId(lowerHexToUnsignedLong(result))
            .build());
      };
    }
  }

  @Test
  public void continues_a_trace_when_only_trace_id_propagated() throws Exception {
    consumerTracing = KafkaTracing.create(Tracing.newBuilder()
        .spanReporter(consumerSpans::add)
        .propagationFactory(new Propagation.Factory() {
          @Override public <K> Propagation<K> create(Propagation.KeyFactory<K> keyFactory) {
            return new TraceIdOnlyPropagation<>(keyFactory);
          }
        })
        .sampler(Sampler.ALWAYS_SAMPLE)
        .build());
    producerTracing = KafkaTracing.create(Tracing.newBuilder()
        .spanReporter(producerSpans::add)
        .propagationFactory(new Propagation.Factory() {
          @Override public <K> Propagation<K> create(Propagation.KeyFactory<K> keyFactory) {
            return new TraceIdOnlyPropagation<>(keyFactory);
          }
        })
        .sampler(Sampler.ALWAYS_SAMPLE)
        .build());

    producer = createTracingProducer();
    consumer = createTracingConsumer();

    producer.send(new ProducerRecord<>(testName.getMethodName(), TEST_KEY, TEST_VALUE)).get();

    ConsumerRecords<String, String> records = consumer.poll(10000);

    assertThat(records).hasSize(1);
    assertThat(producerSpans).hasSize(1);
    assertThat(consumerSpans).hasSize(1);

    assertThat(consumerSpans.getFirst().traceId())
        .isEqualTo(producerSpans.getFirst().traceId());

    for (ConsumerRecord<String, String> record : records) {
      TraceContext forProcessor = consumerTracing.nextSpan(record).context();

      Span consumerSpan = consumerSpans.getLast();
      assertThat(forProcessor.traceIdString()).isEqualTo(consumerSpan.traceId());
    }
  }

  Consumer<String, String> createTracingConsumer(String... topics) {
    if (topics.length == 0) topics = new String[] {testName.getMethodName()};
    KafkaConsumer<String, String> consumer = kafkaRule.helper().createStringConsumer();
    List<TopicPartition> assignments = new ArrayList<>();
    for (String topic : topics) {
      assignments.add(new TopicPartition(topic, 0));
    }
    consumer.assign(assignments);
    return consumerTracing.consumer(consumer);
  }

  Producer<String, String> createTracingProducer() {
    KafkaProducer<String, String> producer = kafkaRule.helper().createStringProducer();
    return producerTracing.producer(producer);
  }
}