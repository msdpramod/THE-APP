package io.theapp.platform.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "the-app.outbox.publisher", name = "enabled", havingValue = "true")
public class OutboxPublisher {

    private final OutboxLeaseStore leaseStore;
    private final OutboxDeliveryGateway deliveryGateway;
    private final String owner = UUID.randomUUID().toString();
    private final int batchSize;
    private final int maxAttempts;
    private final Duration leaseDuration;
    private final Duration baseBackoff;

    public OutboxPublisher(
            OutboxLeaseStore leaseStore,
            OutboxDeliveryGateway deliveryGateway,
            @Value("${the-app.outbox.publisher.batch-size:50}") int batchSize,
            @Value("${the-app.outbox.publisher.max-attempts:8}") int maxAttempts,
            @Value("${the-app.outbox.publisher.lease-seconds:30}") long leaseSeconds,
            @Value("${the-app.outbox.publisher.base-backoff-seconds:2}") long baseBackoffSeconds) {
        this.leaseStore = leaseStore;
        this.deliveryGateway = deliveryGateway;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.leaseDuration = Duration.ofSeconds(Math.max(1, leaseSeconds));
        this.baseBackoff = Duration.ofSeconds(Math.max(1, baseBackoffSeconds));
    }

    @Scheduled(fixedDelayString = "${the-app.outbox.publisher.poll-delay-ms:1000}")
    public void publishAvailable() {
        for (OutboxEvent event : leaseStore.claimBatch(owner, batchSize, leaseDuration)) {
            try {
                deliveryGateway.deliver(event);
                leaseStore.markPublished(event.eventId(), owner);
            } catch (Exception failure) {
                leaseStore.markFailed(event, owner, maxAttempts, baseBackoff, failure);
            }
        }
    }
}
