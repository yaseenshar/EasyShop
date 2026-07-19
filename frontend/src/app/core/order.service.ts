import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, CheckoutRequest, Order, PagedResponse } from './api-types';

@Injectable({ providedIn: 'root' })
export class OrderService {
  private readonly http = inject(HttpClient);

  /**
   * A fresh key per checkout ATTEMPT - generated once and reused across
   * retries of the same attempt (§4.8 idempotency contract). crypto.randomUUID
   * is available in every browser this app targets; no uuid package needed.
   */
  newIdempotencyKey(): string {
    return crypto.randomUUID();
  }

  checkout(request: CheckoutRequest, idempotencyKey: string): Observable<Order> {
    return this.http
      .post<ApiResponse<Order>>('/api/v1/orders', request, {
        headers: { 'Idempotency-Key': idempotencyKey },
      })
      .pipe(map((res) => res.data));
  }

  getOrder(orderId: string): Observable<Order> {
    return this.http.get<ApiResponse<Order>>(`/api/v1/orders/${orderId}`).pipe(map((res) => res.data));
  }

  listMyOrders(page = 0, size = 20): Observable<PagedResponse<Order>> {
    return this.http
      .get<ApiResponse<PagedResponse<Order>>>(`/api/v1/orders?page=${page}&size=${size}`)
      .pipe(map((res) => res.data));
  }
}
