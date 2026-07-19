import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Route-level role gating for the RBAC matrix. Two principles, both from the
 * backend tickets:
 *
 *   1. Guards are UX; the SERVER is the enforcement. A guard that lies open
 *      still ends in a 403 from the service — the guard only spares the user
 *      the round trip.
 *   2. Roles come from /users/me (the server's own reading of the relayed
 *      token via its SecurityContext) — the SPA never decodes anything.
 *
 * Usage in routes:
 *   { path: 'admin/reviews',  canActivate: [roleGuard('ADMIN')], ... }
 *   { path: 'admin/products', canActivate: [roleGuard('ADMIN', 'VENDOR')], ... }
 *   { path: 'orders',         canActivate: [roleGuard('CUSTOMER', 'ADMIN')], ... }
 *
 * The same roles drive header-menu visibility (the design's "Admin · …"
 * entries render only for ADMIN; the products entry additionally for VENDOR
 * once vendor ownership lands).
 *
 * Assumes AuthService.bootstrap() ran via provideAppInitializer, so status
 * is never 'unknown' by the time routing starts.
 */
export const roleGuard =
  (...roles: string[]): CanActivateFn =>
  (_route, state) => {
    const auth = inject(AuthService);
    const router = inject(Router);

    if (!auth.isAuthenticated()) {
      auth.login(state.url); // anonymous → authenticate first, come back here
      return false;
    }
    if (auth.hasAnyRole(...roles)) {
      return true;
    }
    return router.parseUrl('/forbidden'); // authenticated, wrong role — a 403, not a login problem
  };
