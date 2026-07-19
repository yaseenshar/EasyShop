package com.easyshop.review.controller;

import com.easyshop.common.dto.response.ApiResponse;
import com.easyshop.review.dto.ReviewDtos.ProductRatingSummary;
import com.easyshop.review.dto.ReviewDtos.ReviewResponse;
import com.easyshop.review.dto.ReviewDtos.SubmitReviewRequest;
import com.easyshop.review.service.ReviewService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * Identity from the token, never the body - same rule as user-service's
     * /me endpoint (Phase 2): a client-supplied userId in the payload would
     * let anyone submit reviews as anyone else.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ReviewResponse>> submitReview(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody SubmitReviewRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Review submitted for moderation",
                        reviewService.submitReview(userId, request)));
    }

    // Public reads - only APPROVED reviews are ever exposed here.
    @GetMapping("/products/{productId}")
    public ResponseEntity<ApiResponse<Page<ReviewResponse>>> getProductReviews(
            @PathVariable UUID productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                ApiResponse.success(reviewService.getApprovedReviews(productId, page, size)));
    }

    @GetMapping("/products/{productId}/summary")
    public ResponseEntity<ApiResponse<ProductRatingSummary>> getRatingSummary(
            @PathVariable UUID productId) {
        return ResponseEntity.ok(ApiResponse.success(reviewService.getRatingSummary(productId)));
    }

    // ── Moderation endpoints - ADMIN only via method security ────────────
    // @PreAuthorize works because SecurityConfig has @EnableMethodSecurity
    // and the JWT roles claim is mapped to ROLE_* authorities (Phase 2's
    // converter, reused). Defense in depth: the URL rule in SecurityConfig
    // ALSO restricts /moderation/** - belt and suspenders deliberately.

    @GetMapping("/moderation/queue")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Page<ReviewResponse>>> moderationQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                ApiResponse.success(reviewService.getModerationQueue(page, size)));
    }

    @PostMapping("/moderation/{reviewId}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ReviewResponse>> approve(@PathVariable UUID reviewId) {
        return ResponseEntity.ok(
                ApiResponse.success("Review approved", reviewService.moderate(reviewId, true)));
    }

    @PostMapping("/moderation/{reviewId}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<ReviewResponse>> reject(@PathVariable UUID reviewId) {
        return ResponseEntity.ok(
                ApiResponse.success("Review rejected", reviewService.moderate(reviewId, false)));
    }
}