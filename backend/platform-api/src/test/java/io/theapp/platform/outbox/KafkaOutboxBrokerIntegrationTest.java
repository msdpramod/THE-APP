package io.theapp.platform.outbox;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "the-app.outbox.transport=kafka",
        "the-app.outbox.publisher.enabled=false",
        "the-app.matching.consumer.enabled=false",
        "spring.kafka.bootstrap-servers=${THE_APP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}",
        "spring.kafka.producer.acks=all",
        "spring.kafka.producer.properties.enable.idempotence=true"
})
@EnabledIfEnvironmentVariable(named = "THE_APP_KAFKA_IT", matches = "true")
class KafkaOutboxBrokerIntegrationTest {

    @Autowired
    private OutboxLeaseStore leaseStore;

    @Autowired
    private KafkaOutboxDeliveryGateway deliveryGateway;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void publishesRideRequestedOnlyAfterBrokerAcknowledgementAndPreservesEnvelope() {
        String eventId = UUID.randomUUID().toString();
        String bookingId = "ride-" + UUID.randomUUID();
        String payload = "{\"bookingId\":\"" + bookingId + "\",\"riderId\":\"rider-1\",\"pickupLatitude\":17.385,\"pickupLongitude\":78.4867,\"dropoffLatitude\":17.4483,\"dropoffLongitude\":78.3915}";
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO outbox_event
                    (event_id, aggregate_type, aggregate_id, event_type, payload, status, attempts, available_at, created_at)
                VALUES (?, 'RideBooking', ?, 'RideRequested', ?, 'PENDING', 0, ?, ?)
                """, eventId, bookingId, payload, now, now);

        OutboxPublisher publisher = new OutboxPublisher(
                leaseStore,
                deliveryGateway,
                10,
                3,
                30,
                1);

        publisher.publishAvailable();

        Map<String, Object> state = jdbcTemplate.queryForMap(
                "SELECT status, attempts, published_at, last_error FROM outbox_event WHERE event_id = ?",
                eventId);
        assertThat(state.get("status")).isEqualTo("PUBLISHED");
        assertThat(((Number) state.get("attempts")).intValue()).isEqualTo(1);
        assertThat(state.get("published_at")).isNotNull();
        assertThat(state.get("last_error")).isNull();

        ConsumerRecord<String, String> record = consumeOne(eventId);
        assertThat(record.key()).isEqualTo(bookingId);
        assertThat(record.value()).isEqualTo(payload);
        assertThat(header(record, "event-id")).isEqualTo(eventId);
        assertThat(header(record, "event-type")).isEqualTo("RideRequested");
        assertThat(header(record, "aggregate-type")).isEqualTo("RideBooking");
    }

    private ConsumerRecord<String, String> consumeOne(String eventId) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                System.getenv().getOrDefault("THE_APP_KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "foundation-013-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(Collections.singletonList("ride.requested.v1"));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (eventId.equals(header(record, "event-id"))) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("Timed out waiting for Kafka event " + eventId);
    }

    private String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
