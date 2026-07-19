package com.easyshop.catalog.controller;

import com.easyshop.catalog.dto.CategoryDtos.CategoryResponse;
import com.easyshop.catalog.repository.CategoryRepository;
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

    private final CategoryRepository categoryRepository;

    public CategoryController(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> listCategories() {
        List<CategoryResponse> categories = categoryRepository.findAll().stream()
                .map(CategoryResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(categories));
    }
}
