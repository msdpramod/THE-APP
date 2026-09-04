package io.theapp.platform.matching;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "the-app.matching.consumer.enabled=true",
        "the-app.matching.consumer.group-id=foundation-015-driver-matching",
        "the-app.matching.consumer.dead-letter-topic=ride.requested.v1.dlt",
        "the-app.matching.consumer.retry-backoff-ms=10",
        "the-app.matching.consumer.max-retries=1",
        "the-app.outbox.publisher.enabled=false",
        "spring.kafka.bootstrap-servers=${THE_APP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EnabledIfEnvironmentVariable(named = "THE_APP_KAFKA_POSTGRES_IT", matches = "true")
class KafkaPostgresPoisonEventIntegrationTest {

    private static final String TOPIC = "ride.requested.v1";
    private static final String DLT = "ride.requested.v1.dlt";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void malformedRecordIsDeadLetteredAndFollowingValidRecordStillProcesses() throws Exception {
        String poisonEventId = UUID.randomUUID().toString();
        String poisonBookingId = "ride-" + UUID.randomUUID();
        send(poisonEventId, poisonBookingId, "{not-valid-json");

        String validEventId = UUID.randomUUID().toString();
        String validBookingId = "ride-" + UUID.randomUUID();
        send(validEventId, validBookingId, payload(validBookingId, "rider-after-poison"));

        awaitPersisted(validEventId, validBookingId);

        ConsumerRecord<String, String> deadLetter = awaitDeadLetter(poisonBookingId);
        assertThat(deadLetter.topic()).isEqualTo(DLT);
        assertThat(deadLetter.key()).isEqualTo(poisonBookingId);
        assertThat(deadLetter.value()).isEqualTo("{not-valid-json");
        assertThat(header(deadLetter, "event-id")).isEqualTo(poisonEventId);

        assertThat(count("SELECT COUNT(*) FROM consumer_inbox WHERE event_id = ?", poisonEventId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM driver_matching_request WHERE booking_id = ?", poisonBookingId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM consumer_inbox WHERE event_id = ?", validEventId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM driver_matching_request WHERE booking_id = ?", validBookingId)).isEqualTo(1);
    }

    private void send(String eventId, String bookingId, String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, 0, bookingId, payload);
        record.headers().add("event-id", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", "RideRequested".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregate-type", "RIDE_BOOKING".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private ConsumerRecord<String, String> awaitDeadLetter(String expectedKey) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                System.getenv().getOrDefault("THE_APP_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "foundation-015-dlt-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(DLT));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    if (expectedKey.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Timed out waiting for dead-letter record for key " + expectedKey);
    }

    private void awaitPersisted(String eventId, String bookingId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if (count("SELECT COUNT(*) FROM consumer_inbox WHERE event_id = ?", eventId) == 1
                    && count("SELECT COUNT(*) FROM driver_matching_request WHERE booking_id = ?", bookingId) == 1) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for matching persistence for event " + eventId);
    }

    private int count(String sql, String value) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, value);
        return count == null ? 0 : count;
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private String payload(String bookingId, String riderId) {
        return """
                {
                  "bookingId":"%s",
                  "riderId":"%s",
                  "pickup":{"label":"HITEC City","latitude":17.4435,"longitude":78.3772},
                  "dropoff":{"label":"Secunderabad","latitude":17.4399,"longitude":78.4983},
                  "requestedAt":"%s"
                }
                """.formatted(bookingId, riderId, Instant.now());
    }
}
