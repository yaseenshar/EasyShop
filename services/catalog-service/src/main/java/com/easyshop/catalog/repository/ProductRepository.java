package com.easyshop.catalog.repository;

import com.easyshop.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySku(String sku);

    Page<Product> findByCategoryIdAndActiveTrue(UUID categoryId, Pageable pageable);

    Page<Product> findByActiveTrue(Pageable pageable);

    /** Admin view only (includeInactive=true) - sees deactivated products too. */
    Page<Product> findByCategoryId(UUID categoryId, Pageable pageable);
}
