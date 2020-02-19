package traceImporter;

import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;

import java.time.Duration;
import java.util.*;

import org.apache.avro.specific.SpecificRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.TimeWindowedKStream;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;

/**
 * Collects spans for 10 seconds, grouped by the trace id, and forwards the resulting batch to the
 * topic 'span-batches'
 */
public class SpanToTraceReconstructorStream {


  private final Properties streamsConfig = new Properties();

  private final Topology topology;

  private final SchemaRegistryClient registryClient;

  public SpanToTraceReconstructorStream(SchemaRegistryClient schemaRegistryClient) {

    streamsConfig.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaConfig.BROKER);
    streamsConfig.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, KafkaConfig.COMMIT_INTERVAL_MS);
    streamsConfig.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG,
            KafkaConfig.TIMESTAMP_EXTRACTOR);
    streamsConfig.put(StreamsConfig.APPLICATION_ID_CONFIG, KafkaConfig.APP_ID);

    this.registryClient = schemaRegistryClient;

    this.topology = buildTopology();
  }


  public Topology getTopology() {
    return topology;
  }


  private Topology buildTopology() {
    StreamsBuilder builder = new StreamsBuilder();

    KStream<String, EVSpan> explSpanStream =
            builder.stream(KafkaConfig.IN_TOPIC, Consumed.with(Serdes.String(), getAvroSerde(false)));


    // Aggregate Spans to traces and deduplicate similar spans of a trace
    KTable<String, Trace> traceTable =
            explSpanStream.groupByKey().aggregate(Trace::new, (traceId, evSpan, trace) -> {

              // Initialize Span according to first span of the trace
              long evSpanStartTime = evSpan.getStartTime();
              long evSpanEndTime = evSpan.getEndTime();
              if (trace.getSpanList() == null) {
                trace.setSpanList(new ArrayList<>());
                trace.getSpanList().add(evSpan);

                trace.setStartTime(evSpanStartTime);
                trace.setEndTime(evSpanEndTime);
                trace.setOverallRequestCount(1);
                trace.setDuration(evSpanEndTime - evSpanStartTime);

                trace.setTraceCount(1);

                // set initial trace id - do not change, since this is the major key for kafka
                // partitioning
                trace.setTraceId(evSpan.getTraceId());
              } else {

                // TODO
                // Implement
                // - traceDuration
                // - Tracesteps with caller callee each = EVSpan


                // Find duplicates in Trace (via fqn), aggregate based on request count
                // Furthermore, potentially update trace values
                trace.getSpanList()
                        .stream()
                        .filter(s -> s.getOperationName().contentEquals(evSpan.getOperationName()))
                        .findAny()
                        .ifPresentOrElse(s -> {
                                  s.setRequestCount(s.getRequestCount() + 1);
                                  s.setStartTime(Math.min(s.getStartTime(), evSpan.getStartTime()));
                                  s.setEndTime(Math.max(s.getEndTime(), evSpan.getEndTime()));
                                },
                                () -> trace.getSpanList().add(evSpan));
                trace.getSpanList().stream().mapToLong(EVSpan::getStartTime).min().ifPresent(trace::setStartTime);
                trace.getSpanList().stream().mapToLong(EVSpan::getEndTime).max().ifPresent(trace::setEndTime);
                trace.setDuration(trace.getEndTime() - trace.getStartTime());


              }
              return trace;
            }, Materialized.with(Serdes.String(), getAvroSerde(false)));

    KStream<String, Trace> traceStream = traceTable.toStream();

    // Map traces to a new key that resembles all included spans
    KStream<EVSpanKey, Trace> traceIdSpanStream = traceStream.flatMap((key, trace) -> {

      List<KeyValue<EVSpanKey, Trace>> result = new LinkedList<>();

      List<EVSpanData> spanDataList = new ArrayList<>();

      for (EVSpan span : trace.getSpanList()) {
        spanDataList
                .add(new EVSpanData(span.getOperationName(), span.getHostname(), span.getAppName()));
      }

      EVSpanKey newKey = new EVSpanKey(spanDataList);

      result.add(KeyValue.pair(newKey, trace));
      return result;
    });


    // Aggregate traces in 4s intervals
    TimeWindowedKStream<EVSpanKey, Trace> windowedStream =
            traceIdSpanStream.groupByKey(Grouped.with(getAvroSerde(true), getAvroSerde(false)))
                    .windowedBy(TimeWindows.of(Duration.ofSeconds(4)).grace(Duration.ofSeconds(2)));

    KTable<Windowed<EVSpanKey>, Trace> reducedTraceTable =
            windowedStream.aggregate(Trace::new, (sharedTraceKey, trace, reducedTrace) -> {

              if (reducedTrace.getTraceId() == null) {
                reducedTrace = trace;
              } else {
                reducedTrace.setTraceCount(reducedTrace.getTraceCount() + 1);
                // Use the Span list of the latest trace in the group
                // Do so since span list only grow but never loose elements
                reducedTrace.setSpanList(trace.getSpanList());

                // Update start and end time of the trace

                reducedTrace.setStartTime(Math.min(trace.getStartTime(), reducedTrace.getStartTime()));
                reducedTrace.setEndTime(Math.max(trace.getEndTime(), reducedTrace.getEndTime()));
              }

              return reducedTrace;
            }, Materialized.with(getAvroSerde(true), getAvroSerde(false)));
    // .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()));

    KStream<Windowed<EVSpanKey>, Trace> reducedTraceStream = reducedTraceTable.toStream();

    KStream<String, Trace> reducedIdTraceStream = reducedTraceStream.flatMap((key, value) -> {

      List<KeyValue<String, Trace>> result = new LinkedList<>();

      result.add(KeyValue.pair(value.getTraceId(), value));
      return result;
    });

    reducedIdTraceStream.foreach((key, trace) -> {

      List<EVSpan> list = trace.getSpanList();

      list.forEach((val) -> {
        System.out.println(val.getStartTime() + " : " + val.getEndTime() + " für "
                + val.getOperationName() + " mit Anzahl " + val.getRequestCount());
      });

    });


    // TODO Ordering in Trace
    // TODO implement count attribute in Trace -> number of similar traces
    // TODO Reduce traceIdAndAllTracesStream to similiar traces stream (map and reduce)
    // use hash for trace https://docs.confluent.io/current/streams/quickstart.html#purpose

    reducedIdTraceStream.to(KafkaConfig.OUT_TOPIC,
            Produced.with(Serdes.String(), getAvroSerde(false)));
    return builder.build();
  }

  public void run() {

    final KafkaStreams streams = new KafkaStreams(this.topology, streamsConfig);
    streams.cleanUp();
    streams.start();

    Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
  }

  private <T extends SpecificRecord> SpecificAvroSerde<T> getAvroSerde(boolean forKey) {
    final SpecificAvroSerde<T> valueSerde = new SpecificAvroSerde<>(registryClient);
    valueSerde.configure(
            Map.of(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081"),
            forKey);
    return valueSerde;
  }

}

