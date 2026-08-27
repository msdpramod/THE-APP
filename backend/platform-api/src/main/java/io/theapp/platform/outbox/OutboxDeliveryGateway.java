package io.theapp.platform.outbox;

public interface OutboxDeliveryGateway {
    void deliver(OutboxEvent event) throws Exception;
}
