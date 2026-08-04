package com.easyshop.catalog.service;

import com.easyshop.catalog.config.CacheConfig;
import com.easyshop.catalog.dto.CategoryDtos.CategoryListResponse;
import com.easyshop.catalog.dto.CategoryDtos.CategoryResponse;
import com.easyshop.catalog.repository.CategoryRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Categories are the best cache-aside candidate in the whole service and were
 * the one hot read path still going to MySQL on every request: the controller
 * called findAll() directly, and the category list is rendered on essentially
 * every catalog page.
 *
 * They are also the easiest to cache correctly - seeded taxonomy (V2
 * migration) with no write path in this service, so there is nothing to evict
 * and no invalidation to get wrong. A fixed key is enough; the whole list is
 * one entry.
 */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Cacheable(cacheNames = CacheConfig.CATEGORIES_CACHE, key = "'all'", sync = true)
    @Transactional(readOnly = true)
    public CategoryListResponse listCategories() {
        return new CategoryListResponse(categoryRepository.findAll().stream()
                .map(CategoryResponse::from)
                .toList());
    }
}
