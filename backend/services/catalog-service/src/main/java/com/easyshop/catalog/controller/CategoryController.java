package com.easyshop.catalog.controller;

import com.easyshop.catalog.dto.CategoryDtos.CategoryResponse;
import com.easyshop.catalog.service.CategoryService;
import com.easyshop.common.dto.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Fixed taxonomy, public read - same Option A posture as ProductController
 * (see SecurityConfig). No admin CRUD here: categories are seeded data
 * (V2 migration), not a managed resource in this design.
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> listCategories() {
        // Unwrapped here on purpose: the cache needs a record at the root (see
        // CategoryListResponse), the API contract stays a plain JSON array.
        return ResponseEntity.ok(ApiResponse.success(categoryService.listCategories().categories()));
    }
}
