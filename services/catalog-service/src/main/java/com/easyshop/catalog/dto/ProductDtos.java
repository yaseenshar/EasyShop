package com.easyshop.catalog.dto;

import com.easyshop.catalog.entity.Product;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class ProductDtos {

    private ProductDtos() {}

    public record CreateProductRequest(
            @NotBlank String sku,
            @NotBlank String name,
            String description,
            @NotNull @PositiveOrZero BigDecimal price,
            @NotNull UUID categoryId
    ) {}

    public record UpdateProductRequest(
            @NotBlank String name,
            String description,
            @NotNull @PositiveOrZero BigDecimal price
    ) {}

    /**
     * The cached type. A record with only JDK types - serializes to clean
     * JSON with no Hibernate baggage (see ProductService class comment on
     * why we never cache entities).
     */
    public record ProductResponse(
            UUID id,
            String sku,
            String name,
            String description,
            BigDecimal price,
            String currency,
            UUID categoryId,
            boolean active,
            Instant updatedAt
    ) {
        public static ProductResponse from(Product product) {
            return new ProductResponse(
                    product.getId(), product.getSku(), product.getName(),
                    product.getDescription(), product.getPrice(), product.getCurrency(),
                    product.getCategoryId(), product.isActive(), product.getUpdatedAt());
        }
    }
}