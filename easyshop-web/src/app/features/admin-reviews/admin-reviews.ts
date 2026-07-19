import { Component, OnInit, inject, signal } from '@angular/core';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { ReviewService } from '../../core/review.service';
import { CatalogService } from '../../core/catalog.service';
import { ToastService } from '../../core/toast.service';
import { ModerationItem } from '../../core/api-types';

interface ModerationRow {
  item: ModerationItem;
  productName: string;
  stars: string;
}

@Component({
  selector: 'app-admin-reviews',
  imports: [],
  templateUrl: './admin-reviews.html',
  styleUrl: './admin-reviews.css',
})
export class AdminReviews implements OnInit {
  private readonly reviewService = inject(ReviewService);
  private readonly catalogService = inject(CatalogService);
  private readonly toast = inject(ToastService);

  protected readonly rows = signal<ModerationRow[]>([]);
  protected readonly loading = signal(true);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.reviewService.getModerationQueue().subscribe((page) => {
      if (page.content.length === 0) {
        this.rows.set([]);
        this.loading.set(false);
        return;
      }
      forkJoin(
        page.content.map((item) =>
          this.catalogService.getProduct(item.productId).pipe(catchError(() => of(null))),
        ),
      ).subscribe((products) => {
        this.rows.set(
          page.content.map((item, i) => ({
            item,
            productName: products[i]?.name ?? 'Unknown product',
            stars: '★'.repeat(item.rating) + '☆'.repeat(5 - item.rating),
          })),
        );
        this.loading.set(false);
      });
    });
  }

  approve(reviewId: string): void {
    this.reviewService.approve(reviewId).subscribe(() => {
      this.rows.update((rows) => rows.filter((r) => r.item.id !== reviewId));
      this.toast.show('Review approved');
    });
  }

  reject(reviewId: string): void {
    this.reviewService.reject(reviewId).subscribe(() => {
      this.rows.update((rows) => rows.filter((r) => r.item.id !== reviewId));
      this.toast.show('Review rejected');
    });
  }
}
