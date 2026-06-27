package com.easyshop.order.repository;

import com.easyshop.order.outbox.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, java.util.UUID> {

    @Query(value = "SELECT * FROM outbox_events WHERE published = false " +
            "ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> findUnpublishedBatchForUpdate();
}
