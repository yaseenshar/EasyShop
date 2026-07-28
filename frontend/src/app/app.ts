import { Component, ElementRef, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs/operators';
import { AuthService } from './core/auth.service';
import { CartService } from './core/cart.service';
import { CatalogService } from './core/catalog.service';
import { ThemeService } from './core/theme.service';
import { ToastService } from './core/toast.service';
import { WishlistService } from './core/wishlist.service';
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
  protected readonly theme = inject(ThemeService);
  protected readonly wishlist = inject(WishlistService);
  private readonly catalogService = inject(CatalogService);
  private readonly router = inject(Router);

  protected readonly categories = signal<Category[]>([]);
  protected readonly userMenuOpen = signal(false);
  protected readonly mobileMenuOpen = signal(false);
  protected readonly searchOpen = signal(false);
  protected readonly searchTerm = signal('');

  @ViewChild('searchInput') private searchInput?: ElementRef<HTMLInputElement>;

  /**
   * Admin and account-profile screens use their own full-page "dashboard"
   * chrome (dark sidebar + own top bar, per the mockup) instead of the
   * storefront's promo bar / glass nav / footer - without this, both would
   * render stacked on top of each other since every route shares this root
   * component's template.
   */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );
  protected readonly useDashboardChrome = computed(
    () => this.url().startsWith('/admin') || this.url().startsWith('/profile'),
  );

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

  toggleSearch(): void {
    this.searchOpen.update((open) => !open);
    if (this.searchOpen()) {
      setTimeout(() => this.searchInput?.nativeElement.focus());
    }
  }

  submitSearch(): void {
    const q = this.searchTerm().trim();
    if (!q) return;
    void this.router.navigate(['/shop'], { queryParams: { q } });
    this.searchTerm.set('');
    this.searchOpen.set(false);
    this.closeMobileMenu();
  }

  toggleMobileMenu(): void {
    this.mobileMenuOpen.update((open) => !open);
  }

  closeMobileMenu(): void {
    this.mobileMenuOpen.set(false);
  }

  signIn(): void {
    this.auth.login();
  }

  signOut(): void {
    this.closeUserMenu();
    this.auth.logout();
  }
}
