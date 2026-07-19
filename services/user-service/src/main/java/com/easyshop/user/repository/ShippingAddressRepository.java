package com.easyshop.user.repository;

import com.easyshop.user.entity.ShippingAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShippingAddressRepository extends JpaRepository<ShippingAddress, UUID> {

    List<ShippingAddress> findByUser_Id(UUID userId);
}
