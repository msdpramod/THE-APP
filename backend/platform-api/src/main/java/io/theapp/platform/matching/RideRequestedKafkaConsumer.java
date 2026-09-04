package io.theapp.platform.matching;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(prefix = "the-app.matching.consumer", name = "enabled", havingValue = "true")
public class RideRequestedKafkaConsumer {

    private final JsonMapper jsonMapper;
    private final DriverMatchingInboxStore inboxStore;

    public RideRequestedKafkaConsumer(JsonMapper jsonMapper, DriverMatchingInboxStore inboxStore) {
        this.jsonMapper = jsonMapper;
        this.inboxStore = inboxStore;
    }

    @KafkaListener(
            topics = "${the-app.kafka.topics.ride-requested:ride.requested.v1}",
            groupId = "${the-app.matching.consumer.group-id:driver-matching-v1}")
    public void consume(ConsumerRecord<String, String> record) {
        String eventId = requiredHeader(record, "event-id");
        String eventType = requiredHeader(record, "event-type");
        if (!"RideRequested".equals(eventType)) {
            throw new InvalidRideRequestedEventException("Unsupported event type on ride topic: " + eventType);
        }

        DriverMatchingInboxStore.RideRequestedMessage message;
        try {
            message = jsonMapper.readValue(record.value(), DriverMatchingInboxStore.RideRequestedMessage.class);
        } catch (Exception ex) {
            throw new InvalidRideRequestedEventException("Malformed RideRequested payload", ex);
        }

        if (record.key() == null || !record.key().equals(message.bookingId())) {
            throw new InvalidRideRequestedEventException("Kafka key must match RideRequested bookingId");
        }

        inboxStore.accept(eventId, message);
    }

    private String requiredHeader(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length == 0) {
            throw new InvalidRideRequestedEventException("Missing required Kafka header: " + name);
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
