package com.easyshop.order.saga;

import com.easyshop.common.event.PaymentEvents.PaymentFailedEvent;
import com.easyshop.common.saga.SagaMessages.StockReservationReply;
import com.easyshop.order.dto.OrderDtos.CreateOrderRequest;
import com.easyshop.order.dto.OrderDtos.CreateOrderRequest.OrderLineRequest;
import com.easyshop.order.entity.Order;
import com.easyshop.order.entity.Order.OrderStatus;
import com.easyshop.order.entity.OrderSagaState;
import com.easyshop.order.outbox.OutboxEvent;
import com.easyshop.order.repository.OrderRepository;
import com.easyshop.order.repository.OrderSagaRepository;
import com.easyshop.order.repository.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.easyshop.common.metrics.BusinessMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.JpaVendorAdapter;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Forces payment and inventory (stock reservation) failures through
 * OrderSagaOrchestrator's real @KafkaListener-annotated methods - called
 * directly in-process rather than through a real broker, since what's under
 * test is the rollback LOGIC (state transitions + compensating commands +
 * OrderCancelledEvent), not Kafka transport itself. Runs against a real
 * Postgres (Testcontainers, same image as docker-compose) with Flyway
 * migrations applied, so the outbox_events/orders/order_saga_state
 * assertions below exercise the actual schema, not a mocked repository.
 *
 * Deliberately hand-wires a minimal JPA-only context instead of
 * @SpringBootTest: the full app would eagerly resolve a JwtDecoder against
 * issuer-uri (Keycloak) and register with Eureka on startup, neither of
 * which is running here - and Spring Boot 4.1 removed @DataJpaTest /
 * @AutoConfigureTestDatabase entirely (verified: those classes no longer
 * exist in spring-boot-test-autoconfigure 4.1.0), so this wires the same
 * DataSource + Flyway + JPA repository + orchestrator bean set by hand with
 * plain Spring ORM, no test-slice annotation needed.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = OrderSagaOrchestratorRollbackTest.TestConfig.class)
@Testcontainers
class OrderSagaOrchestratorRollbackTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Configuration
    @EnableJpaRepositories(basePackages = "com.easyshop.order.repository")
    @EnableTransactionManagement
    @Import(OrderSagaOrchestrator.class)
    static class TestConfig {

        /**
         * This context is hand-wired rather than a full @SpringBootTest (see the
         * class note), so Boot's metrics auto-configuration never runs and no
         * MeterRegistry exists. A SimpleMeterRegistry keeps the orchestrator's
         * saga-outcome counters real in-process, which lets the rollback tests
         * below assert on them rather than just tolerating them.
         */
        @Bean
        BusinessMetrics businessMetrics() {
            return new BusinessMetrics(new SimpleMeterRegistry());
        }

        @Bean
        DataSource dataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl(postgres.getJdbcUrl());
            ds.setUsername(postgres.getUsername());
            ds.setPassword(postgres.getPassword());
            return ds;
        }

        // initMethod = "migrate" runs the same V1-V3 migrations under
        // src/main/resources/db/migration that production uses - the
        // @DependsOn below the EMF bean guarantees this runs BEFORE
        // Hibernate's ddl-auto=validate check inspects the schema.
        @Bean(initMethod = "migrate")
        Flyway flyway(DataSource dataSource) {
            return Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .table("flyway_schema_history_order")
                    .load();
        }

        @Bean
        JpaVendorAdapter jpaVendorAdapter() {
            return new HibernateJpaVendorAdapter();
        }

        @Bean
        @DependsOn("flyway")
        LocalContainerEntityManagerFactoryBean entityManagerFactory(
                DataSource dataSource, JpaVendorAdapter jpaVendorAdapter) {
            var emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(dataSource);
            emf.setJpaVendorAdapter(jpaVendorAdapter);
            emf.setPackagesToScan("com.easyshop.order.entity", "com.easyshop.order.outbox");
            Properties jpaProperties = new Properties();
            jpaProperties.put("hibernate.hbm2ddl.auto", "validate");
            emf.setJpaProperties(jpaProperties);
            return emf;
        }

        @Bean
        PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean
        PersistenceExceptionTranslationPostProcessor exceptionTranslation() {
            return new PersistenceExceptionTranslationPostProcessor();
        }

        @Bean
        ObjectMapper objectMapper() {
            // Mirrors the production ObjectMapper bean (JavaTimeModule via
            // Spring Boot's Jackson autoconfiguration) - the orchestrator
            // serializes Instant fields into every outbox payload.
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }

    @Autowired
    private OrderSagaOrchestrator orchestrator;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderSagaRepository sagaRepository;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private Order startOrder() {
        var request = new CreateOrderRequest(
                List.of(new OrderLineRequest(UUID.randomUUID(), 2, new BigDecimal("19.99"))),
                UUID.randomUUID());
        return orchestrator.createOrderAndStartSaga(
                UUID.randomUUID(), new BigDecimal("39.98"), request, UUID.randomUUID().toString());
    }

    private List<OutboxEvent> outboxEventsFor(UUID orderId) {
        return outboxRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(orderId))
                .toList();
    }

    @Test
    void paymentFailure_releasesStockAndCancelsOrderViaOrderCancelledEvent() throws Exception {
        Order order = startOrder();
        UUID orderId = order.getId();
        UUID reservationId = UUID.randomUUID();

        // Simulate inventory-service's successful reservation reply, exactly
        // as it would arrive over inventory.stock-reservation.reply.
        orchestrator.onStockReservationReply(
                new StockReservationReply(orderId, true, reservationId, null),
                mock(Acknowledgment.class));

        // Force the payment failure - the case that CAN'T be forced
        // deterministically through the live gateway simulation (it only
        // fails ~8% of the time at random), which is exactly why this is a
        // JUnit test and not a live-stack script.
        var ack = mock(Acknowledgment.class);
        String failurePayload = objectMapper.writeValueAsString(
                new PaymentFailedEvent(orderId, "Card declined by issuer", Instant.now()));
        // onPaymentReply takes the ConsumerRecord since dd98f19 (it needs the
        // headers, not just the payload) and only reads record.value(), so the
        // topic/partition/offset here are placeholders.
        orchestrator.onPaymentReply(
                new ConsumerRecord<>(SagaTopics.PAYMENT_CHARGE_REPLY, 0, 0L, orderId.toString(), failurePayload),
                ack);

        Order reloadedOrder = orderRepository.findById(orderId).orElseThrow();
        OrderSagaState saga = sagaRepository.findById(orderId).orElseThrow();

        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saga.getCurrentStep()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saga.hasComensationRun("RELEASE_STOCK")).isTrue();

        List<OutboxEvent> events = outboxEventsFor(orderId);
        assertThat(events)
                .extracting(OutboxEvent::getEventType)
                .contains("ReleaseStockCommand", "OrderCancelledEvent");

        OutboxEvent releaseCommand = events.stream()
                .filter(e -> e.getEventType().equals("ReleaseStockCommand")).findFirst().orElseThrow();
        assertThat(releaseCommand.getTopic()).isEqualTo(SagaTopics.INVENTORY_RELEASE_COMMAND);

        OutboxEvent cancelledEvent = events.stream()
                .filter(e -> e.getEventType().equals("OrderCancelledEvent")).findFirst().orElseThrow();
        assertThat(cancelledEvent.getTopic()).isEqualTo(SagaTopics.ORDER_EVENTS);
        assertThat(cancelledEvent.getPayload()).contains("Card declined by issuer");

        verify(ack).acknowledge();
    }

    @Test
    void inventoryFailure_cancelsOrderWithoutReleaseCommand() {
        Order order = startOrder();
        UUID orderId = order.getId();

        // Force the stock reservation failure - deterministic in real life
        // too (an oversized quantity), but forced directly here to keep this
        // test isolated to the orchestrator's own rollback branching.
        var ack = mock(Acknowledgment.class);
        orchestrator.onStockReservationReply(
                new StockReservationReply(orderId, false, null, "Insufficient stock: requested 2, available 0"),
                ack);

        Order reloadedOrder = orderRepository.findById(orderId).orElseThrow();
        OrderSagaState saga = sagaRepository.findById(orderId).orElseThrow();

        assertThat(reloadedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(saga.getCurrentStep()).isEqualTo(OrderStatus.CANCELLED);
        // Nothing was ever reserved, so there's nothing to compensate -
        // ReleaseStockCommand must NOT be published for this path.
        assertThat(saga.hasComensationRun("RELEASE_STOCK")).isFalse();

        List<OutboxEvent> events = outboxEventsFor(orderId);
        assertThat(events).extracting(OutboxEvent::getEventType).doesNotContain("ReleaseStockCommand");

        OutboxEvent cancelledEvent = events.stream()
                .filter(e -> e.getEventType().equals("OrderCancelledEvent")).findFirst().orElseThrow();
        assertThat(cancelledEvent.getTopic()).isEqualTo(SagaTopics.ORDER_EVENTS);
        assertThat(cancelledEvent.getPayload()).contains("Insufficient stock");

        verify(ack).acknowledge();
    }
}
