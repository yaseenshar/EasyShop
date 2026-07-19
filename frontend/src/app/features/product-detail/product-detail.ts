import { Component, OnInit, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { CatalogService } from '../../core/catalog.service';
import { CartService } from '../../core/cart.service';
import { ReviewService } from '../../core/review.service';
import { AuthService } from '../../core/auth.service';
import { ToastService } from '../../core/toast.service';
import { Product, RatingSummary, Review } from '../../core/api-types';

@Component({
  selector: 'app-product-detail',
  imports: [CurrencyPipe],
  templateUrl: './product-detail.html',
  styleUrl: './product-detail.css',
})
export class ProductDetail implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly catalogService = inject(CatalogService);
  private readonly cartService = inject(CartService);
  private readonly reviewService = inject(ReviewService);
  private readonly toast = inject(ToastService);
  protected readonly auth = inject(AuthService);

  protected readonly product = signal<Product | null>(null);
  protected readonly categoryName = signal('');
  protected readonly rating = signal<RatingSummary | null>(null);
  protected readonly reviews = signal<Review[]>([]);
  protected readonly qty = signal(1);
  protected readonly loading = signal(true);

  protected readonly reviewRating = signal(5);
  protected readonly reviewTitle = signal('');
  protected readonly reviewBody = signal('');
  protected readonly submittingReview = signal(false);

  private productId = '';

  ngOnInit(): void {
    this.productId = this.route.snapshot.paramMap.get('id')!;
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.catalogService.getProduct(this.productId).subscribe((product) => {
      this.product.set(product);
      this.loading.set(false);
      this.catalogService.listCategories().subscribe((categories) => {
        this.categoryName.set(categories.find((c) => c.id === product.categoryId)?.name ?? '');
      });
    });
    this.catalogService.getRatingSummary(this.productId).subscribe((r) => this.rating.set(r));
    this.reviewService.getApprovedReviews(this.productId).subscribe((page) => this.reviews.set(page.content));
  }

  protected ratingLabel(): string {
    const r = this.rating();
    if (!r || r.reviewCount === 0) return 'No reviews yet';
    const rounded = Math.round(r.averageRating);
    const stars = '★'.repeat(rounded) + '☆'.repeat(5 - rounded);
    return `${stars} · ${r.averageRating.toFixed(1)} (${r.reviewCount} review${r.reviewCount === 1 ? '' : 's'})`;
  }

  starsFor(rating: number): string {
    return '★'.repeat(rating) + '☆'.repeat(5 - rating);
  }

  incQty(): void {
    this.qty.update((q) => Math.min(99, q + 1));
  }

  decQty(): void {
    this.qty.update((q) => Math.max(1, q - 1));
  }

  async addToCart(): Promise<void> {
    const p = this.product();
    if (!p) return;
    await this.cartService.addItem(p.id, p.name, p.price, this.qty());
    this.toast.show(`Added "${p.name}" to cart`);
  }

  setReviewRating(n: number): void {
    this.reviewRating.set(n);
  }

  submitReview(): void {
    if (!this.reviewTitle().trim()) {
      this.toast.show('Add a title before submitting');
      return;
    }
    this.submittingReview.set(true);
    this.reviewService
      .submitReview({
        productId: this.productId,
        rating: this.reviewRating(),
        title: this.reviewTitle(),
        body: this.reviewBody(),
      })
      .subscribe({
        next: () => {
          this.toast.show('Review submitted for moderation');
          this.reviewRating.set(5);
          this.reviewTitle.set('');
          this.reviewBody.set('');
          this.submittingReview.set(false);
        },
        error: (err) => {
          this.submittingReview.set(false);
          const msg = err?.error?.message ?? 'Could not submit review';
          this.toast.show(msg);
        },
      });
  }

  goHome(): void {
    void this.router.navigate(['/']);
  }
}
