package com.easyshop.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.converter.JacksonJsonMessageConverter;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * Mirrors order-service's KafkaConfig (Phase 3, Step 3.6) - same rationale
 * applies here: String-serialized producer (the OutboxPublisher already
 * JSON-serializes before handing off to KafkaTemplate), JsonMessageConverter
 * on the consumer side so @KafkaListener methods can declare typed record
 * parameters directly (ChargePaymentCommand, in this service's case).
 */
@Configuration
public class KafkaConfig {

    // KAFKA_AUTO_CREATE_TOPICS_ENABLE=false on the broker; see order-service's
    // KafkaConfig for the full rationale. Declared here because
    // payment-service is the producer of the charge reply topic.
    @Bean
    public NewTopic paymentChargeReplyTopic() {
        return TopicBuilder.name("payment.charge.reply").partitions(1).replicas(1).build();
    }

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
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
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, JsonMapper objectMapper) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setRecordMessageConverter(new JacksonJsonMessageConverter(objectMapper));
        factory.setConcurrency(1);
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