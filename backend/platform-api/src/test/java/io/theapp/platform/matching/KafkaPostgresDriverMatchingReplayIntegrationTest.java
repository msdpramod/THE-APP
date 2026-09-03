package io.theapp.platform.matching;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "the-app.matching.consumer.enabled=true",
        "the-app.matching.consumer.group-id=foundation-014-driver-matching",
        "the-app.outbox.publisher.enabled=false",
        "spring.kafka.bootstrap-servers=${THE_APP_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EnabledIfEnvironmentVariable(named = "THE_APP_KAFKA_POSTGRES_IT", matches = "true")
class KafkaPostgresDriverMatchingReplayIntegrationTest {

    private static final String TOPIC = "ride.requested.v1";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void duplicateBrokerDeliveryCreatesOneLogicalMatchingRequest() throws Exception {
        String eventId = UUID.randomUUID().toString();
        String bookingId = "ride-" + UUID.randomUUID();
        String payload = payload(bookingId, "rider-replay");

        send(eventId, bookingId, payload);
        awaitPersisted(eventId, bookingId);

        // Force a broker-level replay of the same logical event. A following sentinel on
        // the same partition proves the duplicate was consumed before final assertions.
        send(eventId, bookingId, payload);

        String sentinelEventId = UUID.randomUUID().toString();
        String sentinelBookingId = "ride-" + UUID.randomUUID();
        send(sentinelEventId, sentinelBookingId, payload(sentinelBookingId, "rider-sentinel"));
        awaitPersisted(sentinelEventId, sentinelBookingId);

        assertThat(count("SELECT COUNT(*) FROM consumer_inbox WHERE event_id = ?", eventId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM driver_matching_request WHERE booking_id = ?", bookingId)).isEqualTo(1);

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM driver_matching_request WHERE booking_id = ?",
                String.class,
                bookingId);
        assertThat(status).isEqualTo("PENDING");

        String storedAggregateId = jdbcTemplate.queryForObject(
                "SELECT aggregate_id FROM consumer_inbox WHERE event_id = ?",
                String.class,
                eventId);
        assertThat(storedAggregateId).isEqualTo(bookingId);
    }

    private void send(String eventId, String bookingId, String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, 0, bookingId, payload);
        record.headers().add("event-id", eventId.getBytes(StandardCharsets.UTF_8));
        record.headers().add("event-type", "RideRequested".getBytes(StandardCharsets.UTF_8));
        record.headers().add("aggregate-type", "RIDE_BOOKING".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
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
