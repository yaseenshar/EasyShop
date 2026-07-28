import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { WishlistService } from '../core/wishlist.service';
import { Product } from '../core/api-types';
import { ProductArt } from './product-art';

/**
 * The catalog-grid card used on Home (trending), Shop, product-detail
 * (related products) and Wishlist — one template so those four screens stay
 * visually identical. Add-to-cart is emitted (each parent already owns its
 * own CartService/ToastService wiring); wishlist toggling is handled here
 * directly since WishlistService is a plain app-wide singleton.
 */
@Component({
  selector: 'app-product-card',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, CurrencyPipe, ProductArt],
  template: `
    <div class="product-card">
      <a [routerLink]="['/products', product().id]" class="product-card-media">
        <div class="art-tile">
          <app-product-art [seed]="product().sku" [name]="product().name" />
        </div>
        @if (!product().active) {
          <span class="product-card-unavailable">Unavailable</span>
        }
        <button
          type="button"
          class="wishlist-btn"
          [class.active]="wishlist.has(product().id)"
          [attr.aria-label]="wishlist.has(product().id) ? 'Remove from wishlist' : 'Save to wishlist'"
          (click)="onToggleWishlist($event)"
        >
          <svg viewBox="0 0 24 24" [attr.fill]="wishlist.has(product().id) ? 'currentColor' : 'none'" stroke="currentColor" stroke-width="1.8">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z"
            />
          </svg>
        </button>
      </a>
      <div class="product-card-body">
        <a [routerLink]="['/products', product().id]" class="product-card-name">{{ product().name }}</a>
        @if (ratingLabel()) {
          <div class="product-card-rating stars">{{ ratingLabel() }}</div>
        }
        <div class="product-card-footer">
          <span class="product-card-price">{{ product().price | currency: product().currency }}</span>
          <button
            type="button"
            class="btn btn-primary btn-pill btn-sm"
            [disabled]="!product().active"
            (click)="add.emit(product())"
          >
            {{ addLabel() }}
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [
    `
      .product-card {
        display: flex;
        flex-direction: column;
        background: var(--surface);
        border: 1px solid var(--border);
        border-radius: var(--radius-card);
        overflow: hidden;
        box-shadow: var(--shadow-card);
        transition: transform 0.2s ease, box-shadow 0.2s ease;
        height: 100%;
      }
      .product-card:hover { transform: translateY(-3px); }

      .product-card-media { position: relative; display: block; }
      .product-card-media .art-tile { border-radius: 0; aspect-ratio: 1 / 1; }

      .product-card-unavailable {
        position: absolute;
        inset: 0;
        display: flex;
        align-items: center;
        justify-content: center;
        background: rgba(15, 23, 42, 0.45);
        color: #fff;
        font-size: 13px;
        font-weight: 700;
      }

      .wishlist-btn {
        position: absolute;
        top: 10px;
        right: 10px;
        width: 32px;
        height: 32px;
        border: none;
        border-radius: 50%;
        background: color-mix(in oklch, var(--surface) 88%, transparent);
        color: var(--ink-soft);
        display: flex;
        align-items: center;
        justify-content: center;
        cursor: pointer;
        transition: transform 0.15s ease, color 0.15s ease;
      }
      .wishlist-btn svg { width: 17px; height: 17px; }
      .wishlist-btn:hover { transform: scale(1.1); }
      .wishlist-btn.active { color: var(--primary); }

      .product-card-body {
        display: flex;
        flex-direction: column;
        gap: 6px;
        padding: 14px;
        flex: 1;
      }
      .product-card-name {
        color: var(--ink);
        font-size: 14px;
        font-weight: 700;
        line-height: 1.35;
        display: -webkit-box;
        -webkit-line-clamp: 2;
        -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .product-card-name:hover { color: var(--primary); }
      .product-card-rating { font-size: 12px; }
      .product-card-footer {
        margin-top: auto;
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 8px;
        padding-top: 4px;
      }
      .product-card-price { font-size: 17px; font-weight: 800; color: var(--ink); }
    `,
  ],
})
export class ProductCard {
  readonly product = input.required<Product>();
  readonly ratingLabel = input<string>('');
  readonly addLabel = input<string>('Add to cart');
  readonly add = output<Product>();

  protected readonly wishlist = inject(WishlistService);

  protected onToggleWishlist(event: Event): void {
    event.preventDefault();
    event.stopPropagation();
    this.wishlist.toggle(this.product().id);
  }
}
