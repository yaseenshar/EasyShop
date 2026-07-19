import { Component, OnInit, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { Router, ActivatedRoute } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';
import { CatalogService } from '../../core/catalog.service';
import { CartService } from '../../core/cart.service';
import { ToastService } from '../../core/toast.service';
import { Product, RatingSummary } from '../../core/api-types';

interface ProductCard {
  product: Product;
  ratingLabel: string;
}

@Component({
  selector: 'app-home',
  imports: [CurrencyPipe],
  templateUrl: './home.html',
  styleUrl: './home.css',
})
export class Home implements OnInit {
  private readonly catalogService = inject(CatalogService);
  private readonly cartService = inject(CartService);
  private readonly toast = inject(ToastService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly cards = signal<ProductCard[]>([]);
  protected readonly categoryTitle = signal('All products');
  protected readonly loading = signal(true);

  ngOnInit(): void {
    this.route.queryParamMap
      .pipe(
        map((params) => params.get('categoryId')),
        switchMap((categoryId) => {
          this.loading.set(true);
          return forkJoin({
            categoryId: of(categoryId),
            categories: this.catalogService.listCategories(),
            products: this.catalogService.listProducts(categoryId),
          });
        }),
        switchMap(({ categoryId, categories, products }) => {
          this.categoryTitle.set(
            categoryId ? (categories.find((c) => c.id === categoryId)?.name ?? 'All products') : 'All products',
          );
          if (products.content.length === 0) {
            return of([] as ProductCard[]);
          }
          const summaries = products.content.map((p) =>
            this.catalogService.getRatingSummary(p.id).pipe(catchError(() => of(null))),
          );
          return forkJoin(summaries).pipe(
            map((results) =>
              products.content.map((product, i) => ({
                product,
                ratingLabel: this.formatRating(results[i]),
              })),
            ),
          );
        }),
      )
      .subscribe((cards) => {
        this.cards.set(cards);
        this.loading.set(false);
      });
  }

  private formatRating(summary: RatingSummary | null): string {
    if (!summary || summary.reviewCount === 0) {
      return 'No reviews yet';
    }
    const rounded = Math.round(summary.averageRating);
    const stars = '★'.repeat(rounded) + '☆'.repeat(5 - rounded);
    return `${stars} (${summary.reviewCount})`;
  }

  openProduct(productId: string): void {
    void this.router.navigate(['/products', productId]);
  }

  async addToCart(card: ProductCard, event: Event): Promise<void> {
    event.stopPropagation();
    await this.cartService.addItem(card.product.id, card.product.name, card.product.price, 1);
    this.toast.show(`Added "${card.product.name}" to cart`);
  }
}
