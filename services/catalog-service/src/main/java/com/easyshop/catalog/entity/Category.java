package com.easyshop.catalog.entity;

import jakarta.persistence.*;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(name = "parent_id")
    private UUID parentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Category() {}

    public static Category createNew(String name, String slug) {
        Category category = new Category();
        category.name = name;
        category.slug = slug;
        return category;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }
}
