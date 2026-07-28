import { Component, OnInit, inject } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { CartService } from '../../core/cart.service';
import { ProductArt } from '../../shared/product-art';

@Component({
  selector: 'app-cart',
  imports: [CurrencyPipe, RouterLink, ProductArt],
  templateUrl: './cart.html',
  styleUrl: './cart.css',
})
export class CartPage implements OnInit {
  protected readonly cartService = inject(CartService);
  private readonly router = inject(Router);

  ngOnInit(): void {
    void this.cartService.refresh();
  }

  inc(productId: string, currentQty: number): void {
    void this.cartService.setQuantity(productId, Math.min(99, currentQty + 1));
  }

  dec(productId: string, currentQty: number): void {
    if (currentQty <= 1) return;
    void this.cartService.setQuantity(productId, currentQty - 1);
  }

  remove(productId: string): void {
    void this.cartService.removeItem(productId);
  }

  goCheckout(): void {
    void this.router.navigate(['/checkout']);
  }
}
