package io.theapp.platform.matching;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "the-app.matching.consumer", name = "enabled", havingValue = "true")
public class MatchingKafkaRecoveryConfiguration {

    @Bean
    CommonErrorHandler matchingKafkaErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${the-app.matching.consumer.dead-letter-topic:ride.requested.v1.dlt}") String deadLetterTopic,
            @Value("${the-app.matching.consumer.retry-backoff-ms:1000}") long retryBackoffMs,
            @Value("${the-app.matching.consumer.max-retries:2}") long maxRetries) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(Math.max(0, retryBackoffMs), Math.max(0, maxRetries)));
        errorHandler.addNotRetryableExceptions(InvalidRideRequestedEventException.class);
        return errorHandler;
    }
}
