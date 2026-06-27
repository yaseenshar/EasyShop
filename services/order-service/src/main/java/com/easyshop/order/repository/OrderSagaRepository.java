package com.easyshop.order.repository;

import com.easyshop.order.entity.OrderSagaState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface OrderSagaRepository extends JpaRepository<OrderSagaState, UUID> {
}
