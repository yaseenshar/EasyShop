package com.easyshop.catalog.dto;

import com.easyshop.catalog.entity.Product;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    /**
     * Plain record standing in for Spring Data's Page on the cached/wire
     * boundary. PageImpl has no default constructor and Page is an
     * interface, so a generic JSON redis serializer can round-trip it INTO
     * the cache but not back OUT: on a cache hit it deserializes to a raw
     * LinkedHashMap and ClassCastExceptions on the Page cast. A plain
     * record has neither problem.
     */
    public record PagedResponse<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
        public static <T> PagedResponse<T> from(Page<T> page) {
            return new PagedResponse<>(
                    page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }
}