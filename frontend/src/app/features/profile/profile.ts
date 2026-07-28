import { Component, OnInit, inject, signal } from '@angular/core';
import { AuthService } from '../../core/auth.service';
import { AddressService } from '../../core/address.service';
import { OrderService } from '../../core/order.service';
import { ToastService } from '../../core/toast.service';
import { pillClassForStatus } from '../../core/order-status';
import { Address, Order } from '../../core/api-types';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { DashboardTopbar } from '../../shared/dashboard-topbar';

type Tab = 'dashboard' | 'addresses' | 'settings';

interface OrderRow {
  order: Order;
  pillClass: string;
}

/**
 * Account dashboard (mockup's sidebar profile). Tabs are client-side only -
 * everything here already had a home elsewhere (checkout's address form,
 * order-history's list, the original edit-profile form) so this is a
 * reshuffle of existing service calls into one screen, not new backend work.
 */
@Component({
  selector: 'app-profile',
  imports: [CurrencyPipe, DatePipe, RouterLink, DashboardTopbar],
  templateUrl: './profile.html',
  styleUrl: './profile.css',
})
export class Profile implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly addressService = inject(AddressService);
  private readonly orderService = inject(OrderService);
  private readonly toast = inject(ToastService);

  protected readonly tab = signal<Tab>('dashboard');

  protected readonly recentOrders = signal<OrderRow[]>([]);
  protected readonly loadingOrders = signal(true);

  protected readonly addresses = signal<Address[]>([]);
  protected readonly loadingAddresses = signal(true);
  protected readonly addingAddress = signal(false);
  protected readonly newLabel = signal('Home');
  protected readonly newLine1 = signal('');
  protected readonly newCity = signal('');
  protected readonly newPostalCode = signal('');
  protected readonly newCountryCode = signal('');

  protected readonly editing = signal(false);
  protected readonly firstName = signal('');
  protected readonly lastName = signal('');
  protected readonly phoneNumber = signal('');
  protected readonly saving = signal(false);

  ngOnInit(): void {
    if (this.auth.hasRole('CUSTOMER')) {
      this.orderService.listMyOrders(0, 5).subscribe((page) => {
        this.recentOrders.set(page.content.map((order) => ({ order, pillClass: pillClassForStatus(order.status) })));
        this.loadingOrders.set(false);
      });
    } else {
      this.loadingOrders.set(false);
    }

    this.addressService.list().subscribe((addresses) => {
      this.addresses.set(addresses);
      this.loadingAddresses.set(false);
    });
  }

  selectTab(tab: Tab): void {
    this.tab.set(tab);
  }

  addAddress(): void {
    if (!this.newLine1().trim() || !this.newCity().trim() || !this.newPostalCode().trim() || !this.newCountryCode().trim()) {
      this.toast.show('Fill in address, city, postal code and country');
      return;
    }
    this.addingAddress.set(true);
    this.addressService
      .add({
        label: this.newLabel() || 'Home',
        line1: this.newLine1(),
        city: this.newCity(),
        postalCode: this.newPostalCode(),
        countryCode: this.newCountryCode(),
      })
      .subscribe({
        next: (address) => {
          this.addresses.update((list) => [...list, address]);
          this.auth.addAddressToLocalUser(address);
          this.newLine1.set('');
          this.newCity.set('');
          this.newPostalCode.set('');
          this.newCountryCode.set('');
          this.addingAddress.set(false);
          this.toast.show('Address added');
        },
        error: () => {
          this.addingAddress.set(false);
          this.toast.show('Could not add address');
        },
      });
  }

  startEdit(): void {
    const u = this.auth.user();
    if (!u) return;
    this.firstName.set(u.firstName);
    this.lastName.set(u.lastName);
    this.phoneNumber.set(u.phoneNumber ?? '');
    this.editing.set(true);
  }

  cancelEdit(): void {
    this.editing.set(false);
  }

  async save(): Promise<void> {
    this.saving.set(true);
    try {
      await this.auth.updateProfile({
        firstName: this.firstName(),
        lastName: this.lastName(),
        phoneNumber: this.phoneNumber(),
      });
      this.editing.set(false);
      this.toast.show('Profile updated');
    } finally {
      this.saving.set(false);
    }
  }

  signOut(): void {
    this.auth.logout();
  }
}
