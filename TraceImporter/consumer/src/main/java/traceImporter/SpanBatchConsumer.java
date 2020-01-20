package traceImporter;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.opencensus.proto.trace.v1.Span;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Properties;


public class SpanBatchConsumer implements Runnable {

    private static final String TOPIC = "span-batches";
    private static final Logger LOGGER = LoggerFactory.getLogger(SpanBatchConsumer.class);
    KafkaConsumer<Long, List<EVSpan>> consumer;

    public SpanBatchConsumer() {
        final Properties properties = new Properties();
        properties.put("bootstrap.servers", "localhost:9091");
        properties.put("group.id", "trace-importer-1");
        properties.put("enable.auto.commit", "true");
        properties.put("auto.commit.interval.ms", "1000");

        Deserializer<List<EVSpan>> des = new ListDeserializer(new KafkaAvroDeserializer());
        this.consumer = new KafkaConsumer<>(properties, new LongDeserializer(), des);
    }


    @Override
    public void run() {
        this.consumer.subscribe(Arrays.asList(TOPIC));


        while (true) {
            final ConsumerRecords<Long, List<EVSpan>> records =
                this.consumer.poll(Duration.ofMillis(100));

            for (final ConsumerRecord<Long, List<EVSpan>> record : records) {
                EVSpan s = record.value().get(0);
                LOGGER
                    .info("New batch with {} spans of trace with id {} (KEY {})",
                        record.value().size(),
                        toBase64(s.getTraceId()),
                        record.key());

            }
        }
    }

    private String toBase64(ByteBuffer byteString) {
        return Base64.getEncoder().encodeToString(byteString.array());
    }
}