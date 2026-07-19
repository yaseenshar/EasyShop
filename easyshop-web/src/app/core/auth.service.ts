import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Address, ApiResponse, ProfileUpdate, UserProfile } from './api-types';

export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous';

/**
 * BFF-mode auth. There is NO token code in this application — deliberately.
 * The gateway owns the OAuth2 dance (oauth2Login + TokenRelay); the browser
 * holds only the SESSION cookie, which rides along automatically because the
 * app is same-origin with the gateway (dev proxy / gateway-served in prod).
 *
 * Session detection = one GET /users/me at bootstrap. A 401 there is not an
 * error; it is the answer "anonymous".
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  readonly user = signal<UserProfile | null>(null);
  readonly status = signal<AuthStatus>('unknown');

  readonly isAuthenticated = computed(() => this.status() === 'authenticated');
  readonly roles = computed<readonly string[]>(() => this.user()?.roles ?? []);
  readonly initials = computed(() => {
    const u = this.user();
    return u ? `${u.firstName[0] ?? ''}${u.lastName[0] ?? ''}` : '';
  });

  hasRole(role: string): boolean {
    return this.roles().includes(role);
  }

  hasAnyRole(...roles: string[]): boolean {
    return roles.some((r) => this.hasRole(r));
  }

  /**
   * Register via provideAppInitializer so status is settled before routing —
   * guards must never observe 'unknown'.
   */
  async bootstrap(): Promise<void> {
    try {
      const res = await firstValueFrom(
        this.http.get<ApiResponse<UserProfile>>('/api/v1/users/me'),
      );
      this.user.set(res.data);
      this.status.set('authenticated');
    } catch {
      this.user.set(null);
      this.status.set('anonymous');
    }
  }

  /**
   * PUT /me returns firstName/lastName/phoneNumber freshly, but not
   * roles/addresses (those only ride along on GET /me) - carry the existing
   * ones over so a save never blanks out state the profile page didn't touch.
   */
  async updateProfile(update: ProfileUpdate): Promise<void> {
    const res = await firstValueFrom(
      this.http.put<ApiResponse<UserProfile>>('/api/v1/users/me', update),
    );
    const current = this.user();
    this.user.set({ ...res.data, roles: current?.roles ?? [], addresses: current?.addresses ?? [] });
  }

  /** Keeps the checkout screen's "add address" in sync with the profile page. */
  addAddressToLocalUser(address: Address): void {
    const current = this.user();
    if (!current) return;
    this.user.set({ ...current, addresses: [...current.addresses, address] });
  }

  /**
   * Full-page navigation to the gateway's authorization endpoint — a login is
   * a browser redirect in BFF mode, never an XHR. Navigating there directly
   * means the gateway has no "saved request", so we keep the return URL
   * ourselves and restore it after the post-login bootstrap.
   */
  login(returnUrl: string = location.pathname + location.search): void {
    sessionStorage.setItem('easyshop.returnUrl', returnUrl);
    location.assign('/oauth2/authorization/keycloak');
  }

  consumeReturnUrl(): string {
    const url = sessionStorage.getItem('easyshop.returnUrl') ?? '/';
    sessionStorage.removeItem('easyshop.returnUrl');
    return url;
  }

  /**
   * RP-initiated (single) logout: POST /logout ends the gateway's own
   * WebSession, then GatewaySecurityConfig's OidcClientInitiatedServerLogout-
   * SuccessHandler redirects to Keycloak's end_session_endpoint so Keycloak
   * tears down ITS session too - without that second hop, KEYCLOAK_SESSION
   * stays alive and the very next login() silently re-authenticates the same
   * user with no prompt (empirically observed before this was wired up).
   *
   * MUST be a real top-level navigation, not an XHR/fetch POST: the handler's
   * response is a 302 to Keycloak, which itself 302s back - a fetch() would
   * either follow that cross-origin and hand back an opaque/CORS-blocked
   * response, or (with redirect:'manual') never complete the second hop at
   * all. A submitted <form> is a genuine browser navigation, so every hop -
   * gateway -> Keycloak -> back - runs exactly like clicking a link, and
   * every Set-Cookie/cookie-clear along the way applies normally. Same
   * category of reasoning as login() using location.assign(), not fetch().
   */
  logout(): void {
    this.user.set(null);
    this.status.set('anonymous');
    const form = document.createElement('form');
    form.method = 'POST';
    form.action = '/logout';
    document.body.appendChild(form);
    form.submit();
  }
}
