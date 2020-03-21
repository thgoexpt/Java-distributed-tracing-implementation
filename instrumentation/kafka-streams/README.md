# Brave Kafka Streams instrumentation [EXPERIMENTAL]

Add decorators for Kafka Streams to enable tracing.
* `TracingKafkaClientSupplier` a client supplier which traces poll and send operations.
* `TracingProcessorSupplier` completes a span on `process`
* `TracingTransformerSupplier` completes a span on `transform`

## Setup

First, setup the generic Kafka Streams component like this:
```java
import brave.kafka.streams.KafkaStreamsTracing;

...

kafkaStreamsTracing = KafkaStreamsTracing.create(tracing);
```

To trace a processor in your application use `TracingProcessorSupplier`, provided by instrumentation API:

```java
builder.stream(inputTopic)
       .processor(kafkaStreamsTracing.processor(
            "process",
            customProcessor));
```

To trace a transformer, use `TracingTransformerSupplier`, `TracingValueTransformerSupplier`, or `TracingValueTransformerWithValueSupplier` provided by instrumentation API:

```java
builder.stream(inputTopic)
       .transform(kafkaStreamsTracing.transformer(
           "transformer-1",
           customTransformer))
       .to(outputTopic);
```

```java
builder.stream(inputTopic)
       .transformValue(kafkaStreamsTracing.valueTransformer(
           "transform-value",
           customTransformer))
       .to(outputTopic);
```

```java
builder.stream(inputTopic)
       .transformValueWithKey(kafkaStreamsTracing.valueTransformerWithKey(
           "transform-value-with-key",
           customTransformer))
       .to(outputTopic);
```

Additional transformers have been introduced to cover most common Kafka Streams DSL operations (e.g. `map`, `mapValues`, `foreach`, `peek`, `filter`).

```java
builder.stream(inputTopic)
       .transform(kafkaStreamsTracing.map("map", mapper))
       .to(outputTopic);
```

For flat operations like flatMap, the `flatTransform` method can be used:

```java
builder.stream(inputTopic)
       .flatTransform(kafkaStreamsTracing.flatMap("flat-map", mapper))
       .to(outputTopic);
```

For more details, [see here](https://github.com/openzipkin/brave/blob/master/instrumentation/kafka-streams/src/main/java/brave/kafka/streams/KafkaStreamsTracing.java).

To create a Kafka Streams with Tracing Client Supplier enabled, pass your topology and configuration like this:

```java
KafkaStreams kafkaStreams = kafkaStreamsTracing.kafkaStreams(topology, streamsConfig);
```

## What's happening?
Typically, there are at least two spans involved in traces produces by a Kafka Stream application:
* One created by the Consumers that starts a Stream or Table, by `builder.stream(topic)`.
* One created by the Producer that sends a records to a Stream, by `builder.to(topic)`

By receiving records in a Kafka Streams application with Tracing enabled, the span created, once
a record is received, will inject the span context on the headers of the Record, and it will get
propagated downstream onto the Stream topology. As the span context is stored in the Record Headers,
the Producers at the middle (e.g. `builder.through(topic)`) or at the end of a Stream topology
will reference the initial span, and mark the end of a Stream Process.

If intermediate steps on the Stream topology require tracing, then `TracingProcessorSupplier` and
`TracingTransformerSupplier` will allow you to define a Processor/Transformer where execution is recorded as Span,
referencing the parent context stored on Headers, if available.

### Partitioning

Be aware that operations that require `builder.transformer(...)` will cause re-partitioning when
grouping or joining downstream ([Kafka docs](https://kafka.apache.org/documentation/streams/developer-guide/dsl-api.html#applying-processors-and-transformers-processor-api-integration)).

## Notes

* This tracer is only compatible with Kafka Streams versions including headers support ( > 2.0.0).
