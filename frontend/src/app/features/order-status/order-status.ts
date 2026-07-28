import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe, TitleCasePipe } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { forkJoin, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { OrderService } from '../../core/order.service';
import { CatalogService } from '../../core/catalog.service';
import { AuthService } from '../../core/auth.service';
import { Order } from '../../core/api-types';
import {
  DisplayStatus,
  STALL_AFTER_MS,
  TimelineStep,
  isTerminal,
  pollDelayMs,
  timelineFor,
  toDisplayStatus,
} from '../../core/order-status';
import { Stepper, StepperStep } from '../../shared/stepper';

interface DisplayLine {
  name: string;
  quantity: number;
  total: number;
}

@Component({
  selector: 'app-order-status',
  imports: [CurrencyPipe, DatePipe, TitleCasePipe, Stepper],
  templateUrl: './order-status.html',
  styleUrl: './order-status.css',
})
export class OrderStatusPage implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly orderService = inject(OrderService);
  private readonly catalogService = inject(CatalogService);
  protected readonly auth = inject(AuthService);

  protected readonly order = signal<Order | null>(null);
  protected readonly lines = signal<DisplayLine[]>([]);
  protected readonly timeline = signal<TimelineStep[]>([]);
  protected readonly stalled = signal(false);

  protected readonly displayStatus = computed<DisplayStatus | null>(() => {
    const o = this.order();
    return o ? toDisplayStatus(o.status) : null;
  });

  protected readonly shippingLine = computed<string | null>(() => {
    const o = this.order();
    if (!o) return null;
    return this.auth.user()?.addresses.find((a) => a.id === o.shippingAddressId)?.line ?? null;
  });

  /** The timeline's first 'pending' step is the one in flight right now -
   *  shown as the stepper's numbered "active" circle, not a plain future dot. */
  protected readonly stepperSteps = computed<StepperStep[]>(() => {
    let activeAssigned = false;
    return this.timeline().map((s): StepperStep => {
      if (s.state === 'pending' && !activeAssigned) {
        activeAssigned = true;
        return { label: s.label, state: 'active' };
      }
      return { label: s.label, state: s.state };
    });
  });

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
