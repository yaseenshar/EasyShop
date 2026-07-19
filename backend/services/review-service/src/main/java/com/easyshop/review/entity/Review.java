package com.easyshop.review.entity;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.TINYINT)
    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(name = "verified_purchase", nullable = false)
    private boolean verifiedPurchase;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "moderated_at")
    private Instant moderatedAt;

    protected Review() {}

    public static Review submit(UUID productId, UUID userId, int rating,
                                String title, String body, boolean verifiedPurchase) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("Rating must be 1-5, got: " + rating);
        }
        Review review = new Review();
        review.productId = productId;
        review.userId = userId;
        review.rating = rating;
        review.title = title;
        review.body = body;
        review.verifiedPurchase = verifiedPurchase;
        return review;
    }

    /**
     * Moderation transitions only from PENDING - approving an already-
     * rejected review (or vice versa) is an invalid jump, guarded here
     * exactly like Order.transitionTo() in Phase 3. Small state machines
     * deserve the same discipline as big ones.
     */
    public void approve() {
        requirePending();
        this.status = ReviewStatus.APPROVED;
        this.moderatedAt = Instant.now();
    }

    public void reject() {
        requirePending();
        this.status = ReviewStatus.REJECTED;
        this.moderatedAt = Instant.now();
    }

    private void requirePending() {
        if (this.status != ReviewStatus.PENDING) {
            throw new IllegalStateException(
                    "Review already moderated (status=" + status + ")");
        }
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public enum ReviewStatus {
        PENDING, APPROVED, REJECTED
    }
}