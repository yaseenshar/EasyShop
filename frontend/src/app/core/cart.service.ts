import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiResponse, Cart } from './api-types';

const EMPTY_CART: Cart = { items: [], totalItems: 0, subtotal: 0 };

/**
 * Cart state lives here as a signal so the header badge and the cart page
 * stay in sync without re-fetching on every navigation - every mutation
 * updates the signal from the RESPONSE body (cart-service always returns
 * the full current cart), never by guessing the new state client-side.
 */
@Injectable({ providedIn: 'root' })
export class CartService {
  private readonly http = inject(HttpClient);

  readonly cart = signal<Cart>(EMPTY_CART);

  async refresh(): Promise<void> {
    try {
      const res = await firstValueFrom(this.http.get<ApiResponse<Cart>>('/api/v1/cart'));
      this.cart.set(res.data);
    } catch {
      // Anonymous or no cart yet - treat as empty rather than surfacing an error.
      this.cart.set(EMPTY_CART);
    }
  }

  async addItem(productId: string, name: string, price: number, quantity: number): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<ApiResponse<Cart>>('/api/v1/cart/items', { productId, name, price, quantity }),
    );
    this.cart.set(res.data);
  }

  async setQuantity(productId: string, quantity: number): Promise<void> {
    const res = await firstValueFrom(
      this.http.put<ApiResponse<Cart>>(`/api/v1/cart/items/${productId}`, { quantity }),
    );
    this.cart.set(res.data);
  }

  async removeItem(productId: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.delete<ApiResponse<Cart>>(`/api/v1/cart/items/${productId}`),
    );
    this.cart.set(res.data);
  }

  async clear(): Promise<void> {
    await firstValueFrom(this.http.delete<ApiResponse<void>>('/api/v1/cart'));
    this.cart.set(EMPTY_CART);
  }
}
