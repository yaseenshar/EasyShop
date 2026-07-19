package com.easyshop.inventory.outbox;

import com.easyshop.common.outbox.BaseOutboxRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxRepository extends BaseOutboxRepository<OutboxEvent> {
}