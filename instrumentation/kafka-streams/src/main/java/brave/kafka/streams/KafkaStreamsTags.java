package brave.kafka.streams;

import org.apache.kafka.streams.kstream.Predicate;
import org.apache.kafka.streams.processor.ProcessorContext;

/**
 * Tagging policy is not yet dynamic. The descriptions below reflect static policy.
 */
class KafkaStreamsTags {
  /**
   * Added on {@link KafkaStreamsTracing#nextSpan(ProcessorContext)} when the key not null or
   * empty.
   */
  static final String KAFKA_STREAMS_APPLICATION_ID_TAG = "kafka.streams.application.id";
  static final String KAFKA_STREAMS_TASK_ID_TAG = "kafka.streams.task.id";
  /**
   * Added on {@link KafkaStreamsTracing#nextSpan(ProcessorContext)} by the {@link
   * KafkaStreamsTracing#filter(String, Predicate)} transformer. The tag value is <code>true</code>
   * when the message is filtered out, <code>false</code> otherwise.
   */
  static final String KAFKA_STREAMS_FILTERED_TAG = "kafka.streams.filtered";
}
