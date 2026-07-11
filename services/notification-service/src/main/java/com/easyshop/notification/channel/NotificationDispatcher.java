package com.easyshop.notification.channel;

import lombok.extern.log4j.Log4j2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Dispatches one logical notification to every channel that supports its
 * type, with duplicate suppression in front.
 *
 * Dedupe design: Redis SET NX EX keyed on event identity (orderId + type),
 * TTL 7 days. Same atomic-check-and-claim mechanism as payment-service's
 * idempotency lock (Phase 4), deliberately lighter: no result caching, no
 * DB backstop - because the cost of a rare duplicate email is trivially
 * low compared to a duplicate charge. The rigor of an idempotency
 * mechanism should be proportional to the cost of a duplicate; being able
 * to articulate WHY these two implementations differ is worth more in an
 * interview than either implementation alone.
 */
@Log4j2
@Component
public class NotificationDispatcher {

    private static final Duration DEDUPE_TTL = Duration.ofDays(7);

    private final List<NotificationChannel> channels; // Spring injects ALL implementations
    private final StringRedisTemplate redisTemplate;

    public NotificationDispatcher(List<NotificationChannel> channels,
                                  StringRedisTemplate redisTemplate) {
        this.channels = channels;
        this.redisTemplate = redisTemplate;
    }

    public void dispatch(UUID orderId, UUID userId,
                         NotificationChannel.NotificationType type,
                         String subject, String body) {

        String dedupeKey = "notif:%s:%s".formatted(type, orderId);
        Boolean firstDelivery = redisTemplate.opsForValue()
                .setIfAbsent(dedupeKey, "1", DEDUPE_TTL);

        if (!Boolean.TRUE.equals(firstDelivery)) {
            log.info("Suppressing duplicate {} notification for order {} (Kafka redelivery)",
                    type, orderId);
            return;
        }

        for (NotificationChannel channel : channels) {
            if (channel.supports(type)) {
                channel.send(userId, type, subject, body);
            }
        }
    }
}