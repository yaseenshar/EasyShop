package com.easyshop.order.saga;

/**
 * Centralized topic name constants. Defining these once and importing them
 * everywhere (rather than repeating string literals) avoids a classic class
 * of bug: a typo'd topic name in one service silently creating an unintended
 * new topic that nobody consumes from.
 */
public final class SagaTopics {

    private SagaTopics() {}

    // Commands (order-service publishes, participants consume)
    public static final String INVENTORY_RESERVE_COMMAND = "inventory.reserve-stock.command";
    public static final String INVENTORY_CONFIRM_COMMAND = "inventory.confirm-stock.command";
    public static final String INVENTORY_RELEASE_COMMAND = "inventory.release-stock.command";
    public static final String PAYMENT_CHARGE_COMMAND = "payment.charge.command";

    // Replies (participants publish, order-service consumes)
    public static final String INVENTORY_RESERVE_REPLY = "inventory.stock-reservation.reply";
    public static final String INVENTORY_CONFIRM_REPLY = "inventory.stock-confirmation.reply";
    public static final String PAYMENT_CHARGE_REPLY = "payment.charge.reply";

    // Fan-out events (choreography-style, for non-saga-critical subscribers
    // like notification-service - see Step 3.1's note on mixing patterns)
    public static final String ORDER_EVENTS = "order.events";
}