package com.easyshop.notification.channel;

import java.util.UUID;

/**
 * Strategy interface for notification delivery channels.
 *
 * The GoF Strategy pattern, Spring-style: each implementation is a
 * @Component, and NotificationDispatcher injects List<NotificationChannel> -
 * Spring collects every registered implementation automatically. Adding a
 * new channel (WhatsApp, in-app) = one new class implementing this
 * interface, zero changes anywhere else. That's the Open/Closed Principle
 * as a working mechanism rather than a slide-deck bullet.
 */
public interface NotificationChannel {

    /**
     * @return true if this channel is enabled for this notification type -
     *         lets channels opt in/out per event (e.g. SMS only for
     *         high-value order confirmations, never for cancellations).
     */
    boolean supports(NotificationType type);

    void send(UUID userId, NotificationType type, String subject, String body);

    enum NotificationType {
        ORDER_CONFIRMED, ORDER_CANCELLED
    }
}