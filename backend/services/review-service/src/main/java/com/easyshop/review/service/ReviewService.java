package com.easyshop.review.service;

import com.easyshop.common.exception.DuplicateResourceException;
import com.easyshop.common.exception.ResourceNotFoundException;
import com.easyshop.review.dto.ReviewDtos.ProductRatingSummary;
import com.easyshop.review.dto.ReviewDtos.ReviewResponse;
import com.easyshop.review.dto.ReviewDtos.SubmitReviewRequest;
import com.easyshop.review.entity.Review;
import com.easyshop.review.entity.Review.ReviewStatus;
import com.easyshop.review.repository.ReviewRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Log4j2
@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final PurchaseVerifier purchaseVerifier;

    public ReviewService(ReviewRepository reviewRepository,
                         PurchaseVerifier purchaseVerifier) {
        this.reviewRepository = reviewRepository;
        this.purchaseVerifier = purchaseVerifier;
    }

    @Transactional
    public ReviewResponse submitReview(UUID userId, SubmitReviewRequest request) {
        // Friendly-error check; the DB unique constraint is the real
        // guarantee under concurrent double-submits (defense in depth).
        if (reviewRepository.existsByUserIdAndProductId(userId, request.productId())) {
            throw new DuplicateResourceException(
                    "You have already reviewed this product");
        }

        // Calling through a SEPARATE bean (PurchaseVerifier), not a method
        // on this same class - deliberately. Resilience4j's @CircuitBreaker
        // works via Spring AOP proxies, and a self-invocation
        // (this.checkVerifiedPurchase(...)) bypasses the proxy entirely:
        // the annotation would compile, look correct, and silently never
        // fire. Same trap applies to @Transactional, @Cacheable, @Async.
        // Crossing a bean boundary is what makes the interceptor run -
        // a subtle, high-value thing to know cold in an interview.
        boolean verified = purchaseVerifier.isVerifiedPurchase(userId, request.productId());

        Review review = Review.submit(request.productId(), userId,
                request.rating(), request.title(), request.body(), verified);

        return ReviewResponse.from(reviewRepository.save(review));
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getApprovedReviews(UUID productId, int page, int size) {
        return reviewRepository
                .findByProductIdAndStatus(productId, ReviewStatus.APPROVED,
                        PageRequest.of(page, Math.min(size, 50)))
                .map(ReviewResponse::from);
    }

    @Transactional(readOnly = true)
    public ProductRatingSummary getRatingSummary(UUID productId) {
        var summary = reviewRepository.summarizeRatings(productId);
        return new ProductRatingSummary(
                productId,
                summary.getAverageRating() == null ? 0.0
                        : Math.round(summary.getAverageRating() * 10.0) / 10.0,
                summary.getReviewCount());
    }

    // ── Moderation (ADMIN only - enforced at the controller) ─────────────

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getModerationQueue(int page, int size) {
        return reviewRepository
                .findByStatus(ReviewStatus.PENDING, PageRequest.of(page, Math.min(size, 50)))
                .map(ReviewResponse::from);
    }

    @Transactional
    public ReviewResponse moderate(UUID reviewId, boolean approve) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found: " + reviewId));
        if (approve) {
            review.approve();
        } else {
            review.reject();
        }
        return ReviewResponse.from(review);
    }
}