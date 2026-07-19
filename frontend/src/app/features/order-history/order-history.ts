import { Component, OnInit, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { OrderService } from '../../core/order.service';
import { Order, SagaStatus } from '../../core/api-types';

interface OrderRow {
  order: Order;
  pillClass: string;
}

const STATUS_PILL: Record<SagaStatus, string> = {
  ORDER_CREATED: 'pill-accent',
  RESERVING_STOCK: 'pill-accent',
  CHARGING_PAYMENT: 'pill-accent',
  CONFIRMING_STOCK: 'pill-accent',
  NOTIFYING: 'pill-accent',
  COMPENSATING: 'pill-accent',
  CONFIRMED: 'pill-success',
  CANCELLED: 'pill-danger',
};

@Component({
  selector: 'app-order-history',
  imports: [CurrencyPipe, DatePipe],
  templateUrl: './order-history.html',
  styleUrl: './order-history.css',
})
export class OrderHistory implements OnInit {
  private readonly orderService = inject(OrderService);
  private readonly router = inject(Router);

  protected readonly rows = signal<OrderRow[]>([]);
  protected readonly loading = signal(true);

  ngOnInit(): void {
    this.orderService.listMyOrders().subscribe((page) => {
      this.rows.set(page.content.map((order) => ({ order, pillClass: STATUS_PILL[order.status] })));
      this.loading.set(false);
    });
  }

  open(orderId: string): void {
    void this.router.navigate(['/orders', orderId]);
  }
}
