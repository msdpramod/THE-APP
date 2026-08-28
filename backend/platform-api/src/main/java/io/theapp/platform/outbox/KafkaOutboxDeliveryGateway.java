package io.theapp.platform.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "the-app.outbox", name = "transport", havingValue = "kafka")
public class KafkaOutboxDeliveryGateway implements OutboxDeliveryGateway {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String rideRequestedTopic;
    private final Duration sendTimeout;

    public KafkaOutboxDeliveryGateway(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${the-app.kafka.topics.ride-requested:ride.requested.v1}") String rideRequestedTopic,
            @Value("${the-app.kafka.send-timeout-seconds:10}") long sendTimeoutSeconds) {
        this.kafkaTemplate = kafkaTemplate;
        this.rideRequestedTopic = rideRequestedTopic;
        this.sendTimeout = Duration.ofSeconds(Math.max(1, sendTimeoutSeconds));
    }

    @Override
    public void deliver(OutboxEvent event) throws Exception {
        String topic = topicFor(event);
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.aggregateId(), event.payload());
        record.headers().add(new RecordHeader("event-id", event.eventId().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("event-type", event.eventType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("aggregate-type", event.aggregateType().getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private String topicFor(OutboxEvent event) {
        if ("RideRequested".equals(event.eventType())) {
            return rideRequestedTopic;
        }
        throw new IllegalArgumentException("Unsupported outbox event type: " + event.eventType());
    }
}
