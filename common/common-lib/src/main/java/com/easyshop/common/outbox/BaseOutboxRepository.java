package com.easyshop.common.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;
import java.util.UUID;

@NoRepositoryBean
public interface BaseOutboxRepository <T extends BaseOutboxEvent> extends JpaRepository<T, UUID> {

    @Query(value = "SELECT * FROM outbox_events WHERE published = false " +
            "ORDER BY created_at ASC LIMIT 100 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<T> findUnpublishedBatchForUpdate();
}
