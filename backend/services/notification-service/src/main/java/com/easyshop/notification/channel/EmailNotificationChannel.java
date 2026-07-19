package com.easyshop.notification.channel;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Mock email channel - logs instead of sending. Swap for a real
 * implementation (SES, SendGrid, or spring-boot-starter-mail + SMTP)
 * without touching the dispatcher or listener, since they depend only on
 * the NotificationChannel interface.
 *
 * In a real implementation, the user's email address would be resolved
 * from user-service (by userId) - either via an HTTP interface client
 * (see review-service for the current Boot 4 pattern) or, better for a
 * high-volume notifier, from a local projection maintained by consuming
 * user-events. That resolution is deliberately out of scope for the mock.
 */

@Log4j2
@Component
public class EmailNotificationChannel implements NotificationChannel {

    @Override
    public boolean supports(NotificationType type) {
        return true; // email goes out for every notification type
    }

    @Override
    public void send(UUID userId, NotificationType type, String subject, String body) {
        log.info("[EMAIL] to user={} type={} subject='{}' body='{}'", userId, type, subject, body);
    }
}