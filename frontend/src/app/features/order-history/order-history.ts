import { Component, OnInit, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { OrderService } from '../../core/order.service';
import { pillClassForStatus } from '../../core/order-status';
import { Order } from '../../core/api-types';

interface OrderRow {
  order: Order;
  pillClass: string;
}

const PAGE_SIZE = 10;

@Component({
  selector: 'app-order-history',
  imports: [CurrencyPipe, DatePipe, RouterLink],
  templateUrl: './order-history.html',
  styleUrl: './order-history.css',
})
export class OrderHistory implements OnInit {
  private readonly orderService = inject(OrderService);
  private readonly router = inject(Router);

  protected readonly rows = signal<OrderRow[]>([]);
  protected readonly loading = signal(true);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.orderService.listMyOrders(this.page(), PAGE_SIZE).subscribe((page) => {
      this.rows.set(page.content.map((order) => ({ order, pillClass: pillClassForStatus(order.status) })));
      this.totalPages.set(page.totalPages);
      this.loading.set(false);
    });
  }

  goPage(delta: number): void {
    const next = Math.min(Math.max(0, this.page() + delta), Math.max(0, this.totalPages() - 1));
    if (next === this.page()) return;
    this.page.set(next);
    this.load();
  }

  open(orderId: string): void {
    void this.router.navigate(['/orders', orderId]);
  }
}
