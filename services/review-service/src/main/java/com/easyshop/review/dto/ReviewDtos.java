package com.easyshop.review.dto;

import com.easyshop.review.entity.Review;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.UUID;

public final class ReviewDtos {

    private ReviewDtos() {}

    public record SubmitReviewRequest(
            @NotNull UUID productId,
            @Min(1) @Max(5) int rating,
            @NotBlank @Size(max = 150) String title,
            @Size(max = 5000) String body
    ) {}

    public record ReviewResponse(
            UUID id,
            UUID productId,
            int rating,
            String title,
            String body,
            String status,
            boolean verifiedPurchase,
            Instant createdAt
    ) {
        public static ReviewResponse from(Review review) {
            return new ReviewResponse(
                    review.getId(), review.getProductId(), review.getRating(),
                    review.getTitle(), review.getBody(), review.getStatus().name(),
                    review.isVerifiedPurchase(), review.getCreatedAt());
        }
    }

    public record ProductRatingSummary(
            UUID productId,
            double averageRating,
            long reviewCount
    ) {}
}