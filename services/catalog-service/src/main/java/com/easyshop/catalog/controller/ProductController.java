package com.easyshop.catalog.controller;

import com.easyshop.catalog.dto.ProductDtos.CreateProductRequest;
import com.easyshop.catalog.dto.ProductDtos.PagedResponse;
import com.easyshop.catalog.dto.ProductDtos.ProductResponse;
import com.easyshop.catalog.dto.ProductDtos.UpdateProductRequest;
import com.easyshop.catalog.service.ProductService;
import com.easyshop.common.dto.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductResponse>> getProduct(@PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.success(productService.getProduct(productId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ProductResponse>>> listByCategory(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            // Admin/vendor product table only - the public catalog never sets
            // this, so anonymous/customer browsing is unaffected either way.
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(ApiResponse.success(
                productService.listByCategory(categoryId, page, Math.min(size, 100), includeInactive)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProductResponse>> createProduct(
            @Valid @RequestBody CreateProductRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Product created", productService.createProduct(request)));
    }

    @PutMapping("/{productId}")
    public ResponseEntity<ApiResponse<ProductResponse>> updateProduct(
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("Product updated", productService.updateProduct(productId, request)));
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<ApiResponse<Void>> deactivateProduct(@PathVariable UUID productId) {
        productService.deactivateProduct(productId);
        return ResponseEntity.ok(ApiResponse.success("Product deactivated", null));
    }
}