package com.easyshop.catalog.dto;

import com.easyshop.catalog.entity.Category;

import java.util.UUID;

public final class CategoryDtos {

    private CategoryDtos() {}

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
