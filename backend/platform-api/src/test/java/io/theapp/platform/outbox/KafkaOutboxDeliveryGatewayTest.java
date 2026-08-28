package io.theapp.platform.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaOutboxDeliveryGatewayTest {

    @Test
    void deliversRideRequestedWithStableKeyAndHeaders() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        KafkaOutboxDeliveryGateway gateway = new KafkaOutboxDeliveryGateway(
                kafkaTemplate, "ride.requested.v1", 2);
        OutboxEvent event = new OutboxEvent(
                "evt-123", "RideBooking", "ride-456", "RideRequested",
                "{\"rideId\":\"ride-456\"}", 0, Instant.parse("2026-08-28T00:00:00Z"));

        gateway.deliver(event);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> captor =
                org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();

        assertThat(record.topic()).isEqualTo("ride.requested.v1");
        assertThat(record.key()).isEqualTo("ride-456");
        assertThat(record.value()).isEqualTo("{\"rideId\":\"ride-456\"}");
        assertThat(header(record, "event-id")).isEqualTo("evt-123");
        assertThat(header(record, "event-type")).isEqualTo("RideRequested");
        assertThat(header(record, "aggregate-type")).isEqualTo("RideBooking");
    }

    @Test
    void refusesUnknownEventTypesInsteadOfPublishingToWrongTopic() {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        KafkaOutboxDeliveryGateway gateway = new KafkaOutboxDeliveryGateway(
                kafkaTemplate, "ride.requested.v1", 2);
        OutboxEvent event = new OutboxEvent(
                "evt-999", "Order", "order-1", "UnknownEvent", "{}", 0, Instant.now());

        assertThatThrownBy(() -> gateway.deliver(event))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported outbox event type");
    }

    private String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
