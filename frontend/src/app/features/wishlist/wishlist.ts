import { Component, effect, inject, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { CatalogService } from '../../core/catalog.service';
import { CartService } from '../../core/cart.service';
import { ToastService } from '../../core/toast.service';
import { WishlistService } from '../../core/wishlist.service';
import { ProductCard } from '../../shared/product-card';
import { Product } from '../../core/api-types';

interface WishlistCard {
  product: Product;
}

/**
 * Saved items (mockup's "My Wishlist"). There is no backend wishlist - ids
 * live in WishlistService (localStorage); this screen just resolves each id
 * to its current Product via catalog-service and re-syncs whenever the set
 * changes (e.g. a heart toggled on another tab, via WishlistService's
 * `storage` listener, or "Move to cart" here removing an id).
 */
@Component({
  selector: 'app-wishlist',
  imports: [RouterLink, ProductCard],
  templateUrl: './wishlist.html',
  styleUrl: './wishlist.css',
})
export class Wishlist {
  private readonly catalogService = inject(CatalogService);
  private readonly cartService = inject(CartService);
  private readonly toast = inject(ToastService);
  protected readonly wishlist = inject(WishlistService);

  protected readonly cards = signal<WishlistCard[]>([]);
  protected readonly loading = signal(true);

  constructor() {
    effect(() => {
      const ids = [...this.wishlist.items()];
      untracked(() => this.sync(ids));
    });
  }

  private sync(ids: string[]): void {
    const survivors = this.cards().filter((c) => ids.includes(c.product.id));
    const knownIds = new Set(survivors.map((c) => c.product.id));
    const missing = ids.filter((id) => !knownIds.has(id));

    if (missing.length === 0) {
      this.cards.set(survivors);
      this.loading.set(false);
      return;
    }

    this.loading.set(survivors.length === 0);
    forkJoin(missing.map((id) => this.catalogService.getProduct(id).pipe(catchError(() => of(null))))).subscribe(
      (results) => {
        const fetched = results.filter((p): p is Product => p !== null).map((product) => ({ product }));
        this.cards.set([...survivors, ...fetched]);
        this.loading.set(false);
      },
    );
  }

  async moveToCart(product: Product): Promise<void> {
    await this.cartService.addItem(product.id, product.name, product.price, 1);
    this.wishlist.remove(product.id);
    this.toast.show(`Moved "${product.name}" to cart`);
  }
}
