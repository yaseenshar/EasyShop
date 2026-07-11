package com.easyshop.review.repository;

import com.easyshop.review.entity.Review;
import com.easyshop.review.entity.Review.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    Page<Review> findByProductIdAndStatus(UUID productId, ReviewStatus status, Pageable pageable);

    Page<Review> findByStatus(ReviewStatus status, Pageable pageable);

    /**
     * Aggregate computed on read, served by the
     * (product_id, status, created_at) index. Interface-based projection
     * (RatingSummary) - Spring Data maps the aliased columns to the
     * getter names. Escalation path if product pages make this hot: a
     * materialized product_rating_summary row updated on approve(), or
     * simply a short-TTL cache of this response in catalog-service's
     * existing Redis. Don't materialize until measurement says so.
     */
    @Query("""
        SELECT AVG(r.rating) AS averageRating, COUNT(r) AS reviewCount
        FROM Review r
        WHERE r.productId = :productId AND r.status = 'APPROVED'
        """)
    RatingSummary summarizeRatings(@Param("productId") UUID productId);

    interface RatingSummary {
        Double getAverageRating(); // null when no approved reviews yet
        long getReviewCount();
    }
}