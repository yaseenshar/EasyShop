import { Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { DashboardTopbar } from './dashboard-topbar';

/**
 * Dark sidebar chrome wrapping every /admin/* screen (mockup's "Admin
 * Portal"). Reviews/Dashboard stay ADMIN-only same as before this revamp;
 * VENDOR only ever sees Inventory (AdminDashboard redirects a pure VENDOR
 * straight to /admin/inventory rather than dead-ending on a forbidden page).
 */
@Component({
  selector: 'app-admin-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, DashboardTopbar],
  template: `
    <div class="admin-shell">
      <aside class="admin-sidebar">
        <a routerLink="/" class="admin-brand">
          <img src="logo.png" alt="" class="admin-brand-logo" />
          <span class="admin-brand-word">EasyShop</span>
        </a>
        <span class="admin-portal-label">Admin portal</span>

        <nav class="admin-nav">
          @if (auth.hasRole('ADMIN')) {
            <a routerLink="/admin" routerLinkActive="active" [routerLinkActiveOptions]="{ exact: true }">
              Dashboard
            </a>
          }
          <a routerLink="/admin/inventory" routerLinkActive="active">Inventory</a>
          @if (auth.hasRole('ADMIN')) {
            <a routerLink="/admin/reviews" routerLinkActive="active">Reviews</a>
          }
        </nav>

        <a routerLink="/" class="admin-exit">&larr; Exit admin</a>
      </aside>

      <div class="admin-main">
        <app-dashboard-topbar />
        <main class="admin-page">
          <router-outlet />
        </main>
      </div>
    </div>
  `,
  styles: [
    `
      .admin-shell { display: flex; min-height: 100vh; }

      .admin-sidebar {
        width: 240px;
        flex-shrink: 0;
        background: var(--chrome-bg-2);
        color: var(--chrome-muted);
        border-right: 1px solid var(--chrome-border);
        display: flex;
        flex-direction: column;
        padding: 22px 18px;
        position: sticky;
        top: 0;
        height: 100vh;
      }

      .admin-brand { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
      .admin-brand-logo { height: 28px; width: 28px; object-fit: contain; }
      .admin-brand-word { color: var(--chrome-ink); font-weight: 800; font-size: 16px; }

      .admin-portal-label {
        font-size: 11px;
        font-weight: 700;
        text-transform: uppercase;
        letter-spacing: 0.5px;
        color: var(--accent-2);
        margin-bottom: 28px;
      }

      .admin-nav { display: flex; flex-direction: column; gap: 4px; flex: 1; }
      .admin-nav a {
        padding: 11px 14px;
        border-radius: var(--radius-btn);
        font-size: 14px;
        font-weight: 600;
        color: var(--chrome-muted);
      }
      .admin-nav a:hover { background: color-mix(in oklch, white 8%, transparent); color: var(--chrome-ink); }
      .admin-nav a.active {
        background: color-mix(in oklch, var(--primary) 22%, transparent);
        color: color-mix(in oklch, var(--primary) 40%, white);
      }

      .admin-exit {
        font-size: 13px;
        font-weight: 600;
        color: var(--chrome-muted);
        padding-top: 16px;
        border-top: 1px solid var(--chrome-border);
      }
      .admin-exit:hover { color: var(--chrome-ink); }

      .admin-main { flex: 1; min-width: 0; background: var(--bg); display: flex; flex-direction: column; }
      .admin-page { flex: 1; }

      @media (max-width: 760px) {
        .admin-shell { flex-direction: column; }
        .admin-sidebar { width: 100%; height: auto; position: static; flex-direction: row; flex-wrap: wrap; align-items: center; gap: 12px; padding: 14px 16px; }
        .admin-portal-label { display: none; }
        .admin-nav { flex-direction: row; flex: none; }
        .admin-exit { border-top: none; padding-top: 0; margin-left: auto; }
      }
    `,
  ],
})
export class AdminShell {
  protected readonly auth = inject(AuthService);
}
