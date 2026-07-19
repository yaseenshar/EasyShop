import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { OrderService } from '../../core/order.service';
import { CatalogService } from '../../core/catalog.service';
import { Order } from '../../core/api-types';
import { STALL_AFTER_MS, TimelineStep, isTerminal, pollDelayMs, timelineFor } from '../../core/order-status';

interface DisplayLine {
  name: string;
  quantity: number;
  total: number;
}

@Component({
  selector: 'app-order-status',
  imports: [CurrencyPipe, DatePipe],
  templateUrl: './order-status.html',
  styleUrl: './order-status.css',
})
export class OrderStatusPage implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly orderService = inject(OrderService);
  private readonly catalogService = inject(CatalogService);

  protected readonly order = signal<Order | null>(null);
  protected readonly lines = signal<DisplayLine[]>([]);
  protected readonly timeline = signal<TimelineStep[]>([]);
  protected readonly stalled = signal(false);

  private orderId = '';
  private pollAttempt = 0;
  private startedAt = 0;
  private timer?: ReturnType<typeof setTimeout>;
  private destroyed = false;

  ngOnInit(): void {
    this.orderId = this.route.snapshot.paramMap.get('id')!;
    this.startedAt = Date.now();
    this.load();
  }

  ngOnDestroy(): void {
    this.destroyed = true;
    clearTimeout(this.timer);
  }

  private load(): void {
    this.orderService.getOrder(this.orderId).subscribe((order) => {
      if (this.destroyed) return;
      this.order.set(order);
      this.timeline.set(timelineFor(order.status));
      this.resolveLines(order);

      if (isTerminal(order.status)) {
        return;
      }
      if (Date.now() - this.startedAt > STALL_AFTER_MS) {
        this.stalled.set(true);
        return;
      }
      this.timer = setTimeout(() => this.load(), pollDelayMs(this.pollAttempt++));
    });
  }

  private resolveLines(order: Order): void {
    if (order.items.length === 0) {
      this.lines.set([]);
      return;
    }
    forkJoin(
      order.items.map((item) =>
        this.catalogService.getProduct(item.productId).pipe(catchError(() => of(null))),
      ),
    ).subscribe((products) => {
      this.lines.set(
        order.items.map((item, i) => ({
          name: products[i]?.name ?? 'Product',
          quantity: item.quantity,
          total: item.unitPrice * item.quantity,
        })),
      );
    });
  }

  goOrders(): void {
    void this.router.navigate(['/orders']);
  }

  goHome(): void {
    void this.router.navigate(['/']);
  }
}
