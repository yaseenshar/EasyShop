package com.easyshop.notification.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class SmsNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(SmsNotificationChannel.class);

    @Override
    public boolean supports(NotificationType type) {
        // SMS costs real money per message - only confirmations, not
        // cancellations. This per-channel policy hook is why supports()
        // exists on the strategy interface.
        return type == NotificationType.ORDER_CONFIRMED;
    }

    @Override
    public void send(UUID userId, NotificationType type, String subject, String body) {
        log.info("[SMS] to user={} type={} body='{}'", userId, type, body);
    }
}