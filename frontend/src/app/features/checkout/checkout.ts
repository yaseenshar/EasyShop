import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { Router } from '@angular/router';
import { AddressService } from '../../core/address.service';
import { AuthService } from '../../core/auth.service';
import { CartService } from '../../core/cart.service';
import { OrderService } from '../../core/order.service';
import { ToastService } from '../../core/toast.service';
import { Address } from '../../core/api-types';
import { Stepper, StepperStep } from '../../shared/stepper';

@Component({
  selector: 'app-checkout',
  imports: [CurrencyPipe, Stepper],
  templateUrl: './checkout.html',
  styleUrl: './checkout.css',
})
export class Checkout implements OnInit {
  private readonly addressService = inject(AddressService);
  private readonly auth = inject(AuthService);
  protected readonly cartService = inject(CartService);
  private readonly orderService = inject(OrderService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);

  protected readonly addresses = signal<Address[]>([]);
  protected readonly selectedAddressId = signal<string | null>(null);
  protected readonly loading = signal(true);
  protected readonly placing = signal(false);

  protected readonly checkoutSteps = computed<StepperStep[]>(() => [
    { label: 'Shipping & review', state: this.placing() ? 'done' : 'active' },
    { label: 'Confirmation', state: this.placing() ? 'active' : 'pending' },
  ]);

  // Inline "add address" form, shown when the account has none yet.
  protected readonly newLabel = signal('Home');
  protected readonly newLine1 = signal('');
  protected readonly newCity = signal('');
  protected readonly newPostalCode = signal('');
  protected readonly newCountryCode = signal('');

  ngOnInit(): void {
    void this.cartService.refresh();
    this.addressService.list().subscribe((addresses) => {
      this.addresses.set(addresses);
      const preselected = addresses.find((a) => a.isDefault) ?? addresses[0];
      this.selectedAddressId.set(preselected?.id ?? null);
      this.loading.set(false);
    });
  }

  selectAddress(id: string): void {
    this.selectedAddressId.set(id);
  }

  addAddress(): void {
    if (!this.newLine1().trim() || !this.newCity().trim() || !this.newPostalCode().trim() || !this.newCountryCode().trim()) {
      this.toast.show('Fill in address, city, postal code and country');
      return;
    }
    this.addressService
      .add({
        label: this.newLabel() || 'Home',
        line1: this.newLine1(),
        city: this.newCity(),
        postalCode: this.newPostalCode(),
        countryCode: this.newCountryCode(),
      })
      .subscribe((address) => {
        this.addresses.update((list) => [...list, address]);
        this.selectedAddressId.set(address.id);
        this.auth.addAddressToLocalUser(address);
        this.toast.show('Address added');
      });
  }

  placeOrder(): void {
    const addressId = this.selectedAddressId();
    const items = this.cartService.cart().items;
    if (!addressId || items.length === 0) return;

    this.placing.set(true);
    const idempotencyKey = this.orderService.newIdempotencyKey();
    this.orderService
      .checkout(
        {
          shippingAddressId: addressId,
          items: items.map((line) => ({
            productId: line.productId,
            quantity: line.quantity,
            unitPrice: line.priceSnapshot,
          })),
        },
        idempotencyKey,
      )
      .subscribe({
        next: async (order) => {
          await this.cartService.clear();
          void this.router.navigate(['/orders', order.id]);
        },
        error: () => {
          this.placing.set(false);
          this.toast.show('Could not place order - please try again');
        },
      });
  }
}
