import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, Category, PagedResponse, Product, RatingSummary } from './api-types';

@Injectable({ providedIn: 'root' })
export class CatalogService {
  private readonly http = inject(HttpClient);

  listCategories(): Observable<Category[]> {
    return this.http
      .get<ApiResponse<Category[]>>('/api/v1/categories')
      .pipe(map((res) => res.data));
  }

  /**
   * categoryId omitted -> all products (catalog-service treats it as optional).
   * includeInactive is an admin/vendor concern - the public catalog never sets it.
   */
  listProducts(
    categoryId: string | null,
    page = 0,
    size = 20,
    includeInactive = false,
  ): Observable<PagedResponse<Product>> {
    let url = `/api/v1/products?page=${page}&size=${size}&includeInactive=${includeInactive}`;
    if (categoryId) {
      url += `&categoryId=${categoryId}`;
    }
    return this.http.get<ApiResponse<PagedResponse<Product>>>(url).pipe(map((res) => res.data));
  }

  getProduct(productId: string): Observable<Product> {
    return this.http
      .get<ApiResponse<Product>>(`/api/v1/products/${productId}`)
      .pipe(map((res) => res.data));
  }

  getRatingSummary(productId: string): Observable<RatingSummary> {
    return this.http
      .get<ApiResponse<RatingSummary>>(`/api/v1/reviews/products/${productId}/summary`)
      .pipe(map((res) => res.data));
  }

  createProduct(request: {
    sku: string;
    name: string;
    description: string;
    price: number;
    categoryId: string;
  }): Observable<Product> {
    return this.http
      .post<ApiResponse<Product>>('/api/v1/products', request)
      .pipe(map((res) => res.data));
  }

  updateProduct(
    productId: string,
    request: { name: string; description: string; price: number },
  ): Observable<Product> {
    return this.http
      .put<ApiResponse<Product>>(`/api/v1/products/${productId}`, request)
      .pipe(map((res) => res.data));
  }

  /** Deactivate only - catalog-service has no hard DELETE in this design. */
  deactivateProduct(productId: string): Observable<void> {
    return this.http.delete<ApiResponse<void>>(`/api/v1/products/${productId}`).pipe(map(() => void 0));
  }
}
