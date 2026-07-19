import { Routes } from '@angular/router';
import { roleGuard } from './core/role.guard';

export const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
  },
  {
    path: 'products/:id',
    loadComponent: () =>
      import('./features/product-detail/product-detail').then((m) => m.ProductDetail),
  },
  {
    path: 'cart',
    loadComponent: () => import('./features/cart/cart').then((m) => m.CartPage),
    canActivate: [roleGuard('CUSTOMER')],
  },
  {
    path: 'checkout',
    loadComponent: () => import('./features/checkout/checkout').then((m) => m.Checkout),
    canActivate: [roleGuard('CUSTOMER')],
  },
  {
    path: 'orders',
    loadComponent: () =>
      import('./features/order-history/order-history').then((m) => m.OrderHistory),
    canActivate: [roleGuard('CUSTOMER', 'ADMIN')],
  },
  {
    path: 'orders/:id',
    loadComponent: () =>
      import('./features/order-status/order-status').then((m) => m.OrderStatusPage),
    canActivate: [roleGuard('CUSTOMER', 'ADMIN')],
  },
  {
    path: 'profile',
    loadComponent: () => import('./features/profile/profile').then((m) => m.Profile),
    canActivate: [roleGuard('CUSTOMER', 'VENDOR', 'ADMIN')],
  },
  {
    path: 'admin/reviews',
    loadComponent: () =>
      import('./features/admin-reviews/admin-reviews').then((m) => m.AdminReviews),
    canActivate: [roleGuard('ADMIN')],
  },
  {
    path: 'admin/products',
    loadComponent: () =>
      import('./features/admin-products/admin-products').then((m) => m.AdminProducts),
    canActivate: [roleGuard('ADMIN', 'VENDOR')],
  },
  {
    path: 'forbidden',
    loadComponent: () => import('./features/forbidden/forbidden').then((m) => m.Forbidden),
  },
  {
    path: '**',
    loadComponent: () => import('./features/not-found/not-found').then((m) => m.NotFound),
  },
];
