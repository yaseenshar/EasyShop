package com.easyshop.order.config;

import com.easyshop.order.saga.SagaTopics;
import tools.jackson.databind.json.JsonMapper;
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

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    // KAFKA_AUTO_CREATE_TOPICS_ENABLE=false on the broker (deliberate -
    // implicit topic creation on first produce/consume is a footgun in
    // production). NewTopic beans are the explicit alternative: Spring
    // Kafka's auto-configured KafkaAdmin calls AdminClient.createTopics()
    // for every NewTopic bean in the context at startup, independent of
    // the broker's own auto-create setting. Declared here because
    // order-service is the producer for all five - the saga orchestrator
    // that issues the commands and publishes the fan-out events.
    // 3 partitions (not the fleet default of 1): matches
    // InventorySagaListener#onReserveCommand's concurrency=3, so concurrent
    // reservations for the same product (different orders, keyed by orderId
    // below) actually land on different consumer threads and can race on
    // product_stock's @Version - the contention @Retry(name="stockReservation")
    // exists to handle. Spring Kafka's KafkaAdmin reconciles an existing
    // topic's partition count up to match this bean on next startup.
    @Bean
    public NewTopic inventoryReserveCommandTopic() {
        return TopicBuilder.name(SagaTopics.INVENTORY_RESERVE_COMMAND).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryConfirmCommandTopic() {
        return TopicBuilder.name(SagaTopics.INVENTORY_CONFIRM_COMMAND).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryReleaseCommandTopic() {
        return TopicBuilder.name(SagaTopics.INVENTORY_RELEASE_COMMAND).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentChargeCommandTopic() {
        return TopicBuilder.name(SagaTopics.PAYMENT_CHARGE_COMMAND).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentRefundCommandTopic() {
        return TopicBuilder.name(SagaTopics.PAYMENT_REFUND_COMMAND).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic orderEventsTopic() {
        return TopicBuilder.name(SagaTopics.ORDER_EVENTS).partitions(1).replicas(1).build();
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
        return new KafkaTemplate<>(producerFactory);
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
            ConsumerFactory<String, String> consumerFactory,
            JsonMapper objectMapper) {

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

        return factory;
    }

}
