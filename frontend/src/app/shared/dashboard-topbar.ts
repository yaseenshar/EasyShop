import { ChangeDetectionStrategy, Component, inject, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { CartService } from '../core/cart.service';
import { ThemeService } from '../core/theme.service';
import { WishlistService } from '../core/wishlist.service';

/**
 * Top bar shared by the two "dashboard" shells (Admin, account Profile) —
 * the mockup uses the same hamburger + search + icon-row chrome for both,
 * distinct from the storefront's glass nav. One component so they can't
 * drift apart.
 */
@Component({
  selector: 'app-dashboard-topbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  template: `
    <div class="topbar">
      <button type="button" class="topbar-hamburger" (click)="menuToggle.emit()" aria-label="Toggle menu">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path stroke-linecap="round" stroke-linejoin="round" d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>
      <a routerLink="/shop" class="topbar-search" aria-label="Search products">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
        </svg>
        <span>Search products&hellip;</span>
      </a>

      <div class="topbar-actions">
        <button
          type="button"
          class="icon-btn"
          (click)="theme.toggle()"
          [attr.aria-label]="theme.isDark() ? 'Switch to light mode' : 'Switch to dark mode'"
        >
          @if (theme.isDark()) {
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                d="M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"
              />
            </svg>
          } @else {
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path
                stroke-linecap="round"
                stroke-linejoin="round"
                d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"
              />
            </svg>
          }
        </button>
        <a routerLink="/wishlist" class="icon-btn" aria-label="Wishlist">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              d="M4.318 6.318a4.5 4.5 0 000 6.364L12 20.364l7.682-7.682a4.5 4.5 0 00-6.364-6.364L12 7.636l-1.318-1.318a4.5 4.5 0 00-6.364 0z"
            />
          </svg>
          @if (wishlist.count() > 0) {
            <span class="icon-badge">{{ wishlist.count() }}</span>
          }
        </a>
        @if (auth.hasRole('CUSTOMER')) {
          <a routerLink="/cart" class="icon-btn" aria-label="Cart">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6">
              <path stroke-linecap="round" stroke-linejoin="round" d="M16 11V7a4 4 0 00-8 0v4M5 9h14l1 12H4L5 9z" />
            </svg>
            @if (cart.cart().totalItems > 0) {
              <span class="icon-badge">{{ cart.cart().totalItems }}</span>
            }
          </a>
        }
        <a routerLink="/profile" class="topbar-avatar" [title]="auth.user()?.email ?? ''">
          {{ auth.initials() }}
        </a>
      </div>
    </div>
  `,
  styles: [
    `
      .topbar {
        display: flex;
        align-items: center;
        gap: 16px;
        padding: 14px 24px;
        background: var(--surface);
        border-bottom: 1px solid var(--border);
      }
      .topbar-hamburger {
        display: none;
        border: none;
        background: transparent;
        color: var(--ink-soft);
        padding: 6px;
        border-radius: var(--radius-btn);
        cursor: pointer;
      }
      .topbar-hamburger svg { width: 20px; height: 20px; }
      .topbar-search {
        display: flex;
        align-items: center;
        gap: 10px;
        color: var(--muted);
        background: var(--surface-2);
        border-radius: var(--radius-full);
        padding: 8px 16px;
        font-size: 13px;
        flex: 1;
        max-width: 360px;
      }
      .topbar-search:hover { color: var(--ink); }
      .topbar-search svg { width: 16px; height: 16px; flex-shrink: 0; }

      .topbar-actions { display: flex; align-items: center; gap: 4px; margin-left: auto; }
      .icon-btn {
        position: relative;
        display: flex;
        align-items: center;
        justify-content: center;
        width: 36px;
        height: 36px;
        border: none;
        border-radius: var(--radius-full);
        background: transparent;
        color: var(--ink-soft);
        cursor: pointer;
      }
      .icon-btn svg { width: 19px; height: 19px; }
      .icon-btn:hover { background: var(--surface-2); color: var(--primary); }
      .icon-badge {
        position: absolute;
        top: 1px;
        right: 1px;
        background: var(--primary);
        color: #fff;
        font-size: 10px;
        font-weight: 700;
        min-width: 16px;
        height: 16px;
        border-radius: var(--radius-full);
        display: flex;
        align-items: center;
        justify-content: center;
        padding: 0 3px;
      }
      .topbar-avatar {
        width: 34px;
        height: 34px;
        border-radius: 50%;
        background: linear-gradient(135deg, var(--primary), var(--accent-2));
        color: #fff;
        font-size: 12px;
        font-weight: 700;
        display: flex;
        align-items: center;
        justify-content: center;
        margin-left: 6px;
      }

      @media (max-width: 760px) {
        .topbar-hamburger { display: flex; }
        .topbar-search span { display: none; }
        .topbar-search { max-width: none; width: 40px; justify-content: center; padding: 8px; }
      }
    `,
  ],
})
export class DashboardTopbar {
  protected readonly auth = inject(AuthService);
  protected readonly cart = inject(CartService);
  protected readonly theme = inject(ThemeService);
  protected readonly wishlist = inject(WishlistService);

  readonly menuToggle = output<void>();
}
