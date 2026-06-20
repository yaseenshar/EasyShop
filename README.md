# EasyShop Microservices Architecture

EasyShop is a full-scale, distributed e-commerce platform built to demonstrate production-ready microservices architecture. It breaks down a traditional monolithic retail application into 8 independent, highly cohesive services, focusing on fault tolerance, asynchronous communication, and robust observability.

This repository serves as a blueprint for modern cloud-native development, showcasing advanced backend patterns including event-driven choreography, distributed transactions, and robust API security.

Core Highlights:

Event-Driven Sagas: Implements the Choreography Saga pattern using Apache Kafka to handle complex distributed checkout flows across Order, Payment, and Inventory services, complete with compensating transactions for rollback scenarios.

Resilient Infrastructure: Integrates Resilience4j for circuit breaking, retries, and bulkheads to prevent cascading failures. All state-mutating endpoints are strictly idempotent.

Idempotency & Caching: Utilizes Redis for high-performance session cart management and robust idempotency keys (TTL-based) to guarantee exact-once processing for critical financial and order APIs.

Centralized Security: Secures all service perimeters via an API Gateway and Keycloak (OAuth2/JWT) functioning as the authorization server, enforcing strict Role-Based Access Control (RBAC).

Cloud-Native Observability: fully instrumented with Micrometer, Prometheus, and Grafana for metrics, alongside Logstash/Elasticsearch for centralized structured logging and Jaeger/OpenTelemetry for distributed request tracing.

Containerized & Orchestrated: Automated CI/CD pipelines via GitHub Actions, containerized with Docker, and deployed on Kubernetes with Istio handling mTLS service mesh routing.

Tech Stack:

Backend: Java 21, Spring Boot, Spring Cloud, Spring Data JPA

Messaging & Async: Apache Kafka, Zookeeper, Transactional Outbox Pattern

Databases: PostgreSQL, MySQL, Redis

Security: Keycloak (OAuth2 + JWT), Spring Security

DevOps & Infrastructure: Docker, Kubernetes, Helm, Istio, GitHub Actions

Observability: ELK Stack, Prometheus, Grafana, Jaeger

Tags / Topics (For GitHub Repository Topics)
spring-boot microservices kafka kubernetes ecommerce saga-pattern java-21 redis keycloak distributed-systems event-driven-architecture
