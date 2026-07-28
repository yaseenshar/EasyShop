import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';
import { CatalogService } from '../../core/catalog.service';
import { CartService } from '../../core/cart.service';
import { ToastService } from '../../core/toast.service';
import { ProductCard } from '../../shared/product-card';
import { ProductArt } from '../../shared/product-art';
import { Category, Product, RatingSummary } from '../../core/api-types';

interface TrendingCard {
  product: Product;
  ratingLabel: string;
  ratingValue: number;
}

/**
 * Landing page: hero, category tiles, top-rated ("trending") row. The
 * filterable full catalog lives at /shop (features/shop) - this screen is
 * just the storefront entrance the mockup calls for.
 */
@Component({
  selector: 'app-home',
  imports: [ProductCard, ProductArt, RouterLink],
  templateUrl: './home.html',
  styleUrl: './home.css',
})
export class Home implements OnInit {
  private readonly catalogService = inject(CatalogService);
  private readonly cartService = inject(CartService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);

  protected readonly categories = signal<Category[]>([]);
  protected readonly trending = signal<TrendingCard[]>([]);
  protected readonly loading = signal(true);

  ngOnInit(): void {
    this.catalogService.listCategories().subscribe((categories) => this.categories.set(categories));

    this.catalogService
      .listProducts(null, 0, 24)
      .pipe(
        switchMap((page) => {
          if (page.content.length === 0) return of([] as TrendingCard[]);
          const summaries = page.content.map((p) =>
            this.catalogService.getRatingSummary(p.id).pipe(catchError(() => of(null))),
          );
          return forkJoin(summaries).pipe(
            map((results) =>
              page.content
                .map((product, i): TrendingCard => ({
                  product,
                  ratingLabel: this.formatRating(results[i]),
                  ratingValue: results[i]?.averageRating ?? 0,
                }))
                .sort((a, b) => b.ratingValue - a.ratingValue)
                .slice(0, 8),
            ),
          );
        }),
      )
      .subscribe((cards) => {
        this.trending.set(cards);
        this.loading.set(false);
      });
  }

  private formatRating(summary: RatingSummary | null): string {
    if (!summary || summary.reviewCount === 0) return 'No reviews yet';
    const rounded = Math.round(summary.averageRating);
    return '★'.repeat(rounded) + '☆'.repeat(5 - rounded) + ` (${summary.reviewCount})`;
  }

  goShop(categoryId?: string): void {
    void this.router.navigate(['/shop'], categoryId ? { queryParams: { categoryId } } : {});
  }

  async addToCart(product: Product): Promise<void> {
    await this.cartService.addItem(product.id, product.name, product.price, 1);
    this.toast.show(`Added "${product.name}" to cart`);
  }
}
