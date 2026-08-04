package com.easyshop.catalog.dto;

import com.easyshop.catalog.entity.Category;

import java.util.List;
import java.util.UUID;

public final class CategoryDtos {

    private CategoryDtos() {}

    /**
     * The cached type for the category listing - same reason PagedResponse
     * exists below the products DTOs: a bare List cannot be the ROOT of a
     * cached value here.
     *
     * With default typing enabled, the serializer writes a type id alongside
     * each value and reads it back through an erased Object.class. A root-level
     * List round-trips to a JSON array whose type id the reader then cannot
     * find where it expects one, and the cache hit dies with
     * "Unexpected token (JsonToken.START_ARRAY), expected JsonToken.VALUE_STRING
     * ... that contains type id". A record at the root serializes with its own
     * type id and carries the list safely as a field.
     *
     * The wrapper is deliberately not exposed on the wire - CategoryController
     * unwraps it so the API contract stays a plain JSON array.
     */
    public record CategoryListResponse(List<CategoryResponse> categories) {}

    public record CategoryResponse(
            UUID id,
            String name,
            String slug
    ) {
        public static CategoryResponse from(Category category) {
            return new CategoryResponse(category.getId(), category.getName(), category.getSlug());
        }
    }
}
