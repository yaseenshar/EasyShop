package com.easyshop.order.repository;

import com.easyshop.order.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {
    Optional<Order> findByIdempotencyKey(String idempotencyKey);

    Page<Order> findByUserId(UUID userId, Pageable pageable);

    @Query("""
            SELECT COUNT(o) > 0 FROM Order o JOIN o.items i
             WHERE o.userId = :userId AND i.productId = :productId
            AND o.status = 'CONFIRMED'
            """)
   boolean hasConfirmedPurchase(@Param("userId") UUID userId,
                                 @Param("productId") UUID productId);
}
