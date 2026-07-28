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

  ngOnInit(): void {
    this.orderService.listMyOrders().subscribe((page) => {
      this.rows.set(page.content.map((order) => ({ order, pillClass: pillClassForStatus(order.status) })));
      this.loading.set(false);
    });
  }

  open(orderId: string): void {
    void this.router.navigate(['/orders', orderId]);
  }
}
