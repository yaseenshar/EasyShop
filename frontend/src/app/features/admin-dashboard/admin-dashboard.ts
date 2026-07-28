import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError, map } from 'rxjs/operators';
import { AuthService } from '../../core/auth.service';
import { CatalogService } from '../../core/catalog.service';
import { ReviewService } from '../../core/review.service';
import { ModerationItem } from '../../core/api-types';

interface CategoryBar {
  name: string;
  active: number;
  inactive: number;
}

interface ModerationRow {
  item: ModerationItem;
  productName: string;
  stars: string;
}

/**
 * Mockup's "Executive Dashboard", scoped to what's actually knowable: there
 * is no admin-wide orders or user listing in this system (OrderController
 * only exposes the caller's own orders + a per-id lookup; UserController has
 * no listing endpoint at all) - so no revenue chart, no registered-customer
 * count. What IS real, and rendered in the same visual language as the
 * mockup's paired blue/emerald bar chart and status-pill table: catalog
 * active/inactive split PER CATEGORY (catalog-service) and the review
 * moderation backlog (review-service) - both already used elsewhere.
 */
@Component({
  selector: 'app-admin-dashboard',
  imports: [RouterLink, DecimalPipe],
  templateUrl: './admin-dashboard.html',
  styleUrl: './admin-dashboard.css',
})
export class AdminDashboard implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly catalogService = inject(CatalogService);
  private readonly reviewService = inject(ReviewService);

  protected readonly loading = signal(true);
  protected readonly totalProducts = signal(0);
  protected readonly activeProducts = signal(0);
  protected readonly inactiveProducts = signal(0);
  protected readonly categoryCount = signal(0);
  protected readonly pendingReviews = signal(0);

  protected readonly categoryBars = signal<CategoryBar[]>([]);
  protected readonly maxBarValue = computed(() =>
    Math.max(1, ...this.categoryBars().flatMap((b) => [b.active, b.inactive])),
  );

  protected readonly moderationRows = signal<ModerationRow[]>([]);
  protected readonly loadingModeration = signal(true);

  ngOnInit(): void {
    // Product/Vendor-scoped accounts only ever manage inventory - keep them
    // out of the ADMIN-only KPI view rather than dead-ending on /forbidden.
    if (!this.auth.hasRole('ADMIN')) {
      void this.router.navigate(['/admin/inventory']);
      return;
    }

    this.catalogService.listCategories().subscribe((categories) => {
      this.categoryCount.set(categories.length);

      forkJoin({
        all: this.catalogService.listProducts(null, 0, 1, true),
        active: this.catalogService.listProducts(null, 0, 1, false),
      }).subscribe(({ all, active }) => {
        this.totalProducts.set(all.totalElements);
        this.activeProducts.set(active.totalElements);
        this.inactiveProducts.set(Math.max(0, all.totalElements - active.totalElements));
        this.loading.set(false);
      });

      if (categories.length === 0) {
        this.categoryBars.set([]);
        return;
      }
      forkJoin(
        categories.map((cat) =>
          forkJoin({
            active: this.catalogService.listProducts(cat.id, 0, 1, false),
            all: this.catalogService.listProducts(cat.id, 0, 1, true),
          }).pipe(
            map(
              ({ active, all }): CategoryBar => ({
                name: cat.name,
                active: active.totalElements,
                inactive: Math.max(0, all.totalElements - active.totalElements),
              }),
            ),
          ),
        ),
      ).subscribe((bars) => this.categoryBars.set(bars));
    });

    this.reviewService.getModerationQueue(0, 5).subscribe((page) => {
      this.pendingReviews.set(page.totalElements);
      if (page.content.length === 0) {
        this.moderationRows.set([]);
        this.loadingModeration.set(false);
        return;
      }
      forkJoin(
        page.content.map((item) =>
          this.catalogService.getProduct(item.productId).pipe(catchError(() => of(null))),
        ),
      ).subscribe((products) => {
        this.moderationRows.set(
          page.content.map((item, i) => ({
            item,
            productName: products[i]?.name ?? 'Unknown product',
            stars: '★'.repeat(item.rating) + '☆'.repeat(5 - item.rating),
          })),
        );
        this.loadingModeration.set(false);
      });
    });
  }

  barHeight(value: number): number {
    return Math.round((value / this.maxBarValue()) * 100);
  }
}
