package com.easyshop.notification.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka config with the retry + Dead Letter Topic policy that Phases 3-5's
 * services should eventually adopt too (they currently rely on Kafka's
 * plain redelivery; this is the more production-complete pattern, and this
 * service is where it matters most because notification failures are the
 * most LIKELY - email providers are the flakiest dependency in the stack).
 *
 * Flow: listener throws -> DefaultErrorHandler retries with exponential
 * backoff (1s, 2s, 4s... capped, max ~5 attempts) -> if still failing, the
 * DeadLetterPublishingRecoverer publishes the original record to
 * "<original-topic>.DLT" (order.events.DLT) and the consumer moves on.
 * The DLT is then a monitored queue: alert on depth > 0, inspect in Kafka
 * UI, replay after fixing the root cause.
 *
 * Why retry AND DLT rather than either alone: retries handle transient
 * blips (provider hiccup) without human involvement; the DLT handles
 * persistent failures without blocking the partition (head-of-line
 * blocking - everything behind the poison message waits) and without
 * losing the message.
 *
 * API note: DefaultErrorHandler + DeadLetterPublishingRecoverer is
 * long-stable Spring Kafka API (since 2.8) and current sources still show
 * it in the 4.x line - moderate-confidence flag: if anything moved in
 * Spring Kafka 4.1, check docs.spring.io/spring-kafka under
 * "Handling Exceptions".
 */
@Configuration
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // Producer exists solely for the DLT recoverer to publish failed records.
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        // OBSERVATION MUST BE SET ON THE BEAN, NOT IN application.yml.
        // spring.kafka.template.observation-enabled only configures Boot's
        // AUTO-CONFIGURED template, and this hand-built bean replaces it - the
        // same trap the ack-mode comment on the listener factory below records.
        // The property binds without error and does nothing, which is why the
        // saga ran end to end while producing no Kafka spans at all.
        //
        // This is what injects the W3C traceparent header into every record, so
        // the consumer on the other side can continue the trace instead of
        // starting a fresh one.
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Routes exhausted-retry records to "<topic>.DLT", same partition.
        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        // 1s initial, doubling, 10s cap, ~30s total before giving up.
        var backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(10_000L);
        backOff.setMaxElapsedTime(30_000L);

        var handler = new DefaultErrorHandler(recoverer, backOff);

        // Failures that will fail IDENTICALLY on every attempt skip retries
        // and go straight to the DLT - retrying a deserialization error or
        // an NPE just delays the inevitable while blocking the partition.
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class,
                NullPointerException.class,
                com.fasterxml.jackson.core.JacksonException.class
        );

        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            JsonMapper jsonMapper, DefaultErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setRecordMessageConverter(new JacksonJsonMessageConverter(jsonMapper));
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(2);
        // This hand-built factory bypasses Boot's auto-configured one, so
        // spring.kafka.listener.ack-mode in application.yml is never read -
        // it has to be set here instead. Listeners take an Acknowledgment
        // param and need MANUAL_IMMEDIATE or Spring throws IllegalStateException.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // Consumer side of the same story: reads the traceparent off the record
        // and continues the producer's trace. Both halves are required - with
        // only the producer set, spans are emitted but every consumer still
        // roots its own disconnected trace.
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }
}