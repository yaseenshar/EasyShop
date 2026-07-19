import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { CartService } from './core/cart.service';
import { CatalogService } from './core/catalog.service';
import { ToastService } from './core/toast.service';
import { Category } from './core/api-types';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  protected readonly auth = inject(AuthService);
  protected readonly cart = inject(CartService);
  protected readonly toast = inject(ToastService);
  private readonly catalogService = inject(CatalogService);

  protected readonly categories = signal<Category[]>([]);
  protected readonly userMenuOpen = signal(false);

  ngOnInit(): void {
    this.catalogService.listCategories().subscribe((categories) => this.categories.set(categories));
    if (this.auth.isAuthenticated()) {
      void this.cart.refresh();
    }
  }

  toggleUserMenu(): void {
    this.userMenuOpen.update((open) => !open);
  }

  closeUserMenu(): void {
    this.userMenuOpen.set(false);
  }

  signIn(): void {
    this.auth.login();
  }

  signOut(): void {
    this.auth.logout();
  }
}
