import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';
import { CatalogService } from '../../core/catalog.service';
import { CartService } from '../../core/cart.service';
import { ToastService } from '../../core/toast.service';
import { ProductCard } from '../../shared/product-card';
import { COLOR_FILTERS, colorKeyForSeed } from '../../shared/palette';
import { Category, Product, RatingSummary } from '../../core/api-types';

type SortMode = 'featured' | 'price-asc' | 'price-desc' | 'rating' | 'new';

interface ShopCard {
  product: Product;
  ratingLabel: string;
  ratingValue: number;
  colorKey: string;
}

const PAGE_SIZE = 12;
const SEARCH_PAGE_SIZE = 200; // no server-side search endpoint - fetch broadly, filter client-side
const PRICE_CEILING = 2000;

/**
 * The full, filterable catalog (mockup's "Shop Collection"). Category comes
 * from the `categoryId` query param; price/rating/color/sort are client-side
 * refinements over the loaded page since catalog-service's /products
 * endpoint has no price/rating/color/sort/search params. Rating filters real
 * rating-summary data already fetched per card; color filters the same hue
 * already painted on each card's placeholder art (see shared/palette.ts) -
 * both are honestly functional, not decorative.
 */
@Component({
  selector: 'app-shop',
  imports: [ProductCard],
  templateUrl: './shop.html',
  styleUrl: './shop.css',
})
export class Shop implements OnInit {
  private readonly catalogService = inject(CatalogService);
  private readonly cartService = inject(CartService);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly categories = signal<Category[]>([]);
  protected readonly selectedCategoryId = signal<string | null>(null);
  protected readonly selectedCategoryName = signal<string>('All products');
  protected readonly searchQuery = signal<string | null>(null);
  protected readonly sort = signal<SortMode>('featured');
  protected readonly maxPrice = signal(PRICE_CEILING);
  protected readonly priceCeiling = PRICE_CEILING;
  protected readonly minRating = signal(0);
  protected readonly colorFilters = COLOR_FILTERS;
  protected readonly selectedColor = signal<string | null>(null);

  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);
  protected readonly cards = signal<ShopCard[]>([]);
  protected readonly loading = signal(true);

  protected readonly visibleCards = computed(() => {
    let filtered = this.cards().filter((c) => c.product.price <= this.maxPrice());
    if (this.minRating() > 0) filtered = filtered.filter((c) => c.ratingValue >= this.minRating());
    if (this.selectedColor()) filtered = filtered.filter((c) => c.colorKey === this.selectedColor());
    if (this.searchQuery()) {
      const q = this.searchQuery()!.toLowerCase();
      filtered = filtered.filter(
        (c) => c.product.name.toLowerCase().includes(q) || c.product.sku.toLowerCase().includes(q),
      );
    }

    const sorted = [...filtered];
    switch (this.sort()) {
      case 'price-asc':
        sorted.sort((a, b) => a.product.price - b.product.price);
        break;
      case 'price-desc':
        sorted.sort((a, b) => b.product.price - a.product.price);
        break;
      case 'rating':
        sorted.sort((a, b) => b.ratingValue - a.ratingValue);
        break;
      case 'new':
        sorted.sort(
          (a, b) => new Date(b.product.updatedAt).getTime() - new Date(a.product.updatedAt).getTime(),
        );
        break;
    }
    return sorted;
  });

  ngOnInit(): void {
    this.catalogService.listCategories().subscribe((categories) => this.categories.set(categories));

    this.route.queryParamMap
      .pipe(
        map((params) => ({
          categoryId: params.get('categoryId'),
          sort: params.get('sort'),
          q: params.get('q'),
        })),
        switchMap(({ categoryId, sort, q }) => {
          this.selectedCategoryId.set(categoryId);
          this.searchQuery.set(q);
          if (sort === 'new') this.sort.set('new');
          this.page.set(0);
          return this.load();
        }),
      )
      .subscribe();
  }

  private load() {
    this.loading.set(true);
    const size = this.searchQuery() ? SEARCH_PAGE_SIZE : PAGE_SIZE;
    return this.catalogService.listProducts(this.selectedCategoryId(), this.page(), size).pipe(
      switchMap((pageResult) => {
        this.totalPages.set(pageResult.totalPages);
        this.totalElements.set(pageResult.totalElements);
        this.selectedCategoryName.set(
          this.searchQuery()
            ? `Results for "${this.searchQuery()}"`
            : this.selectedCategoryId()
              ? (this.categories().find((c) => c.id === this.selectedCategoryId())?.name ?? 'Products')
              : 'All products',
        );
        if (pageResult.content.length === 0) return of([] as ShopCard[]);
        const summaries = pageResult.content.map((p) =>
          this.catalogService.getRatingSummary(p.id).pipe(catchError(() => of(null))),
        );
        return forkJoin(summaries).pipe(
          map((results) =>
            pageResult.content.map(
              (product, i): ShopCard => ({
                product,
                ratingLabel: this.formatRating(results[i]),
                ratingValue: results[i]?.averageRating ?? 0,
                colorKey: colorKeyForSeed(product.sku),
              }),
            ),
          ),
        );
      }),
      map((cards) => {
        this.cards.set(cards);
        this.loading.set(false);
      }),
    );
  }

  private formatRating(summary: RatingSummary | null): string {
    if (!summary || summary.reviewCount === 0) return 'No reviews yet';
    const rounded = Math.round(summary.averageRating);
    return '★'.repeat(rounded) + '☆'.repeat(5 - rounded) + ` (${summary.reviewCount})`;
  }

  selectCategory(categoryId: string | null): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { categoryId },
      queryParamsHandling: 'merge',
    });
  }

  changePrice(value: string): void {
    this.maxPrice.set(Number(value));
  }

  changeSort(value: string): void {
    this.sort.set(value as SortMode);
  }

  setMinRating(value: number): void {
    this.minRating.set(this.minRating() === value ? 0 : value);
  }

  toggleColor(key: string): void {
    this.selectedColor.set(this.selectedColor() === key ? null : key);
  }

  clearFilters(): void {
    this.maxPrice.set(this.priceCeiling);
    this.minRating.set(0);
    this.selectedColor.set(null);
  }

  goPage(delta: number): void {
    const next = Math.min(Math.max(0, this.page() + delta), Math.max(0, this.totalPages() - 1));
    if (next === this.page()) return;
    this.page.set(next);
    this.load().subscribe();
  }

  async addToCart(product: Product): Promise<void> {
    await this.cartService.addItem(product.id, product.name, product.price, 1);
    this.toast.show(`Added "${product.name}" to cart`);
  }
}
