package com.easyshop.catalog.service;

import com.easyshop.catalog.config.CacheConfig;
import com.easyshop.catalog.dto.ProductDtos.CreateProductRequest;
import com.easyshop.catalog.dto.ProductDtos.PagedResponse;
import com.easyshop.catalog.dto.ProductDtos.ProductResponse;
import com.easyshop.catalog.dto.ProductDtos.UpdateProductRequest;
import com.easyshop.catalog.entity.Product;
import com.easyshop.catalog.repository.ProductRepository;
import com.easyshop.common.exception.ResourceNotFoundException;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The cache-aside pattern, expressed declaratively.
 *
 * IMPORTANT SUBTLETY - what we cache: the RESPONSE DTO (a record), never
 * the JPA ENTITY. Caching a managed entity is a classic trap: the entity
 * carries Hibernate proxy state and lazy-loading handles that either fail
 * to serialize or, worse, serialize successfully and then blow up with
 * LazyInitializationException when a later request deserializes it and
 * touches an uninitialized association outside any session. DTOs are plain
 * data - they serialize cleanly and carry no session baggage.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * sync = true: on a cache MISS for a given key, only ONE thread per
     * JVM executes the method; concurrent callers for the same key block
     * and receive that thread's result. This is the single-flight guard
     * against a hot-key stampede (product goes viral -> its entry expires
     * -> 500 concurrent requests all miss simultaneously -> 500 identical
     * DB queries). Verified nuance from current sources: sync=true
     * coordinates at the Spring/JVM level - with 3 replicas you'd still
     * get up to 3 concurrent loaders, one per instance, which is fine;
     * the goal is collapsing 500 to 3, not 500 to 1. (Redis-level locking
     * via lockingRedisCacheWriter exists but locks the whole cache, not
     * per entry - too coarse for this.)
     */
    @Cacheable(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#productId", sync = true)
    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        return ProductResponse.from(product);
    }

    /**
     * Listings are cached per (category, page, size) tuple. Two rules keep
     * this sane: (1) only cache the FIRST few pages - page 47 of a category
     * is requested so rarely that caching it just fills Redis with dead
     * entries, and (2) the short 2-minute TTL from CacheConfig bounds
     * staleness instead of trying to evict every affected page tuple on
     * every product change, which is unwinnable.
     *
     * condition (not unless) for rule (1): Spring's caching abstraction
     * throws IllegalStateException if sync=true is combined with unless
     * (unless needs the post-invocation #result, which is incompatible
     * with the lock-based get-or-compute semantics of sync). condition is
     * evaluated pre-invocation from the method args only, which is all
     * "only cache page <= 3" actually needs, and IS supported with sync.
     */
    @Cacheable(cacheNames = CacheConfig.PRODUCT_LISTINGS_CACHE,
            key = "#categoryId + ':' + #page + ':' + #size",
            condition = "#page <= 3 && !#includeInactive",
            sync = true)
    @Transactional(readOnly = true)
    public PagedResponse<ProductResponse> listByCategory(UUID categoryId, int page, int size,
                                                          boolean includeInactive) {
        PageRequest pageRequest = PageRequest.of(page, size);
        Page<Product> products = includeInactive
                ? (categoryId == null
                        ? productRepository.findAll(pageRequest)
                        : productRepository.findByCategoryId(categoryId, pageRequest))
                : (categoryId == null
                        ? productRepository.findByActiveTrue(pageRequest)
                        : productRepository.findByCategoryIdAndActiveTrue(categoryId, pageRequest));
        return PagedResponse.from(products.map(ProductResponse::from));
    }

    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {
        Product product = Product.createNew(request.sku(), request.name(),
                request.description(), request.price(), request.categoryId());
        // No eviction needed on CREATE for the detail cache (nothing cached
        // for a brand-new ID) - and the listings cache self-heals within its
        // 2-minute TTL. If "new product visible instantly in its category"
        // were a hard requirement, we'd evict the listings cache here too.
        return ProductResponse.from(productRepository.save(product));
    }

    /**
     * On UPDATE: evict the product's detail entry (precise, by key) AND
     * clear the listings cache entirely (allEntries=true - because we
     * cannot know which page tuples contain this product, and enumerating
     * them is the unwinnable game noted above; a full clear of a 2-minute
     * cache is cheap).
     *
     * Because the CacheManager is transactionAware() (see CacheConfig),
     * these evictions fire AFTER this transaction commits - so a rollback
     * leaves the cache untouched, and no other request can re-populate the
     * cache with pre-update data in the window between eviction and commit.
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#productId"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCT_LISTINGS_CACHE, allEntries = true)
    })
    @Transactional
    public ProductResponse updateProduct(UUID productId, UpdateProductRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        product.updateDetails(request.name(), request.description(), request.price());
        return ProductResponse.from(product);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#productId"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCT_LISTINGS_CACHE, allEntries = true)
    })
    @Transactional
    public void deactivateProduct(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        product.deactivate();
    }
}