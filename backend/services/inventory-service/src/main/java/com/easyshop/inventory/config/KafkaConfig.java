package com.easyshop.inventory.config;

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

    // KAFKA_AUTO_CREATE_TOPICS_ENABLE=false on the broker; see order-service's
    // KafkaConfig for the full rationale. Declared here because
    // inventory-service is the producer of both saga reply topics.
    @Bean
    public NewTopic inventoryReservationReplyTopic() {
        return TopicBuilder.name("inventory.stock-reservation.reply").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryConfirmationReplyTopic() {
        return TopicBuilder.name("inventory.stock-confirmation.reply").partitions(1).replicas(1).build();
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