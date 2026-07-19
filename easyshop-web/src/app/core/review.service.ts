import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiResponse, ModerationItem, PagedResponse, Review, SubmitReviewRequest } from './api-types';

@Injectable({ providedIn: 'root' })
export class ReviewService {
  private readonly http = inject(HttpClient);

  getApprovedReviews(productId: string, page = 0, size = 20): Observable<PagedResponse<Review>> {
    return this.http
      .get<ApiResponse<PagedResponse<Review>>>(
        `/api/v1/reviews/products/${productId}?page=${page}&size=${size}`,
      )
      .pipe(map((res) => res.data));
  }

  submitReview(request: SubmitReviewRequest): Observable<Review> {
    return this.http
      .post<ApiResponse<Review>>('/api/v1/reviews', request)
      .pipe(map((res) => res.data));
  }

  getModerationQueue(page = 0, size = 20): Observable<PagedResponse<ModerationItem>> {
    return this.http
      .get<ApiResponse<PagedResponse<ModerationItem>>>(
        `/api/v1/reviews/moderation/queue?page=${page}&size=${size}`,
      )
      .pipe(map((res) => res.data));
  }

  approve(reviewId: string): Observable<Review> {
    return this.http
      .post<ApiResponse<Review>>(`/api/v1/reviews/moderation/${reviewId}/approve`, null)
      .pipe(map((res) => res.data));
  }

  reject(reviewId: string): Observable<Review> {
    return this.http
      .post<ApiResponse<Review>>(`/api/v1/reviews/moderation/${reviewId}/reject`, null)
      .pipe(map((res) => res.data));
  }
}
