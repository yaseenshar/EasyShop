import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/**
 * BFF mode: this interceptor attaches NOTHING — no tokens exist in the
 * browser to attach. Its job is to carry the backend's 401-vs-403 diagnostic
 * discipline (§6.3) into the SPA:
 *
 *   401 → no/expired session → authentication problem → start login.
 *         The gateway's content negotiation guarantees XHRs get 401 (not a
 *         302), which is exactly what verify-token-relay.sh asserts.
 *   403 → authenticated but not authorized → an RBAC answer, NOT a login
 *         problem. Re-logging-in cannot fix a 403; surface it (forbidden
 *         view / toast with the ApiResponse errorCode) and never redirect.
 *
 * The /users/me bootstrap probe is exempt from the 401 redirect — its 401
 * is the normal "anonymous" answer, not a failure.
 *
 * Design note: auto-login on 401 means "view cart while anonymous" flows
 * straight into Keycloak — the intended UX for this app. If a gentler
 * sign-in prompt is ever preferred, replace login() with an event and keep
 * the 403 branch untouched.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);

  return next(req).pipe(
    catchError((err: unknown) => {
      if (err instanceof HttpErrorResponse && err.status === 401 && !req.url.includes('/users/me')) {
        auth.login();
      }
      return throwError(() => err);
    }),
  );
};
