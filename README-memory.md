# EasyShop Memory Optimization — Budget, Recipes, Verification

## The budget (worst case at limits)

| Container            | Limit  | Notes                                        |
|----------------------|--------|----------------------------------------------|
| service-discovery    | 320 MB |                                              |
| api-gateway          | 384 MB | Netty/WebFlux                                |
| user-service         | 320 MB | JPA                                          |
| order-service        | 384 MB | JPA + Kafka + scheduler                      |
| payment-service      | 384 MB | JPA + Kafka + Redis                          |
| inventory-service    | 384 MB | JPA + Kafka                                  |
| catalog-service      | 352 MB | JPA + Redis cache                            |
| review-service       | 352 MB | JPA + HTTP client                            |
| cart-service         | 288 MB | Redis only                                   |
| notification-service | 288 MB | Kafka + Redis only                           |
| keycloak             | 640 MB | heap capped at 384m                          |
| kafka (KRaft)        | 512 MB | heap capped at 256m; ZOOKEEPER DELETED       |
| mysql                | 448 MB | performance-schema OFF                       |
| postgres             | 256 MB |                                              |
| redis                | 160 MB | maxmemory 128mb, AOF on                      |
| **TOTAL (always-on)**| **~5.4 GB** | vs ~8+ GB unconstrained before          |

Realistic steady-state RSS runs well below limits (limits are ceilings,
not reservations) — expect ~4–4.5 GB in `docker stats`, leaving room for
the OS and your IDE. Zookeeper (-~500 MB) and the three profile-gated
tools (-~800 MB) account for much of the win; the JVM flags do the rest.

## One-time migration to KRaft

Old Zookeeper-era Kafka data is not KRaft-compatible:

    docker compose -f infra/docker-compose.yml down
    docker volume rm easyshop_kafka-data 2>/dev/null || true
    # remove any old zookeeper volume too, then:
    docker compose -f infra/docker-compose.yml up -d
    # re-create your topics (auto-create is off):
    docker compose -f infra/docker-compose.yml exec kafka kafka-topics --create --topic order.events --partitions 3 --replication-factor 1 --bootstrap-server localhost:9092
    # ...repeat for the saga command/reply topics + order.events.DLT

## Startup recipes — run slices, not the world

    # Everything (domain + infra), no tools:
    docker compose -f infra/docker-compose.yml up -d

    # + Kafka UI only while you're debugging topics:
    docker compose -f infra/docker-compose.yml --profile tools up -d kafka-ui
    docker compose -f infra/docker-compose.yml stop kafka-ui        # when done

    # + observability only during Phase 9 work:
    docker compose -f infra/docker-compose.yml --profile observability up -d

    # SLICE: catalog/caching work (~2.2 GB total)
    docker compose -f infra/docker-compose.yml up -d service-discovery api-gateway mysql redis keycloak catalog-service

    # SLICE: auth/user work (~2.1 GB)
    docker compose -f infra/docker-compose.yml up -d service-discovery api-gateway postgres redis keycloak user-service

    # SLICE: full saga path (~3.8 GB)
    docker compose -f infra/docker-compose.yml up -d service-discovery api-gateway postgres mysql redis kafka keycloak \
        order-service payment-service inventory-service notification-service

    # SLICE: cart work (~1.9 GB)
    docker compose -f infra/docker-compose.yml up -d service-discovery api-gateway redis keycloak cart-service

## Verify it worked

    # Live per-container usage — the numbers that used to be 400-600 MB
    # per service should now read ~150-280 MB:
    docker stats --no-stream

    # Proof the flags applied — every service log should show:
    docker compose -f infra/docker-compose.yml logs user-service | grep "Picked up JAVA_TOOL_OPTIONS"

    # Proof the JVM respected the container limit (heap sized from 320m,
    # not from 8 GB):
    docker compose -f infra/docker-compose.yml exec user-service java -XX:MaxRAMPercentage=70 -XX:+PrintFlagsFinal -version | grep MaxHeapSize

## If a service OOMs after this

`-XX:+ExitOnOutOfMemoryError` makes it die visibly (restart + exit code 137
/ OOMKilled in `docker inspect`) rather than limp. Fix = raise THAT
service's mem_limit by 64 MB steps, not everyone's. If it's Metaspace
errors specifically ("OutOfMemoryError: Metaspace"), raise
MaxMetaspaceSize to 192m — Spring apps with many auto-configurations can
brush against 160m.

## Dev-only flags to remove for load testing / production

- `-XX:TieredStopAtLevel=1`  (C1-only JIT: fine for dev, wrong for throughput)
- `-XX:+UseSerialGC` on anything with a heap > ~512 MB (use G1 there)
- Tomcat max threads 20 and Hikari pool 5 are dev sizings

## The interview framing this buys you

This exercise is Kubernetes resource management in miniature: mem_limit is
`resources.limits.memory`, the OOM-kill behavior is identical (exit 137 /
OOMKilled), and MaxRAMPercentage-vs-limit is exactly how you size JVM pods.
"Your Java pods keep getting OOMKilled — walk me through it" is a stock
senior interview question, and the answer is this file: the JVM's RAM
ergonomics, the fixed native costs beyond heap (Metaspace, code cache,
thread stacks), and why the container limit and the JVM's self-imposed
ceiling must be set TOGETHER, with headroom between them for the
non-heap memory.

## The advanced option (mentioned, not built): GraalVM native image

Spring Boot 4 supports compiling services to native executables
(`mvn -Pnative native:compile`): RSS drops to ~50-100 MB per service and
startup to tens of milliseconds — the entire fleet would fit in ~1.5 GB.
Costs: multi-minute builds, reflection/proxy configuration friction
(Resilience4j and some Spring AOP need reachability metadata), and a
different performance profile (no JIT). Worth knowing as the endgame
answer for memory-constrained microservices; not worth the build friction
for daily development here.
