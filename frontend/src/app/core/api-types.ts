/**
 * api-types.ts — the wire contract between the Angular app and the gateway.
 * Verified directly against each service's actual controller/DTO classes
 * during the backend audit (not just the original design mock).
 */

/** Matches common-lib's ApiResponse envelope. */
export interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
  errorCode?: string; // GlobalExceptionHandler codes: FORBIDDEN, UNAUTHORIZED, VALIDATION_ERROR, ...
}

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/* ---------------------------------------------------------------- catalog */

export interface Category {
  id: string;
  name: string;
  slug: string;
}

export interface Product {
  id: string;
  sku: string;
  name: string;
  description: string;
  price: number;
  currency: string;
  categoryId: string;
  active: boolean;
  updatedAt: string;
}

export interface CreateProductRequest {
  sku: string;
  name: string;
  description: string;
  price: number;
  categoryId: string;
}

export interface UpdateProductRequest {
  name: string;
  description: string;
  price: number;
}

/** review-service's ProductRatingSummary, field names verbatim. */
export interface RatingSummary {
  productId: string;
  averageRating: number;
  reviewCount: number;
}

/* ------------------------------------------------------------------- cart */

/** Snapshot pattern: cart-service remembers name/price at add-time. */
export interface CartLine {
  productId: string;
  nameSnapshot: string;
  priceSnapshot: number;
  quantity: number; // clamped 1..99 server-side
}

export interface Cart {
  items: CartLine[];
  totalItems: number;
  subtotal: number;
}

export interface AddItemRequest {
  productId: string;
  name: string;
  price: number;
  quantity: number;
}

/* ----------------------------------------------------------------- orders */

/** Full saga vocabulary — the backend's states, verbatim. */
export type SagaStatus =
  | 'ORDER_CREATED'
  | 'RESERVING_STOCK'
  | 'CHARGING_PAYMENT'
  | 'CONFIRMING_STOCK'
  | 'NOTIFYING'
  | 'COMPENSATING'
  | 'CONFIRMED'
  | 'CANCELLED';

/** order-service does not store a product name snapshot - the UI resolves
 *  display names from catalog-service (see OrderService.enrichItems). */
export interface OrderItem {
  productId: string;
  quantity: number;
  unitPrice: number;
}

export interface Order {
  id: string;
  status: SagaStatus;
  totalAmount: number;
  currency: string;
  shippingAddressId: string;
  items: OrderItem[];
  createdAt: string;
}

export interface CheckoutLineRequest {
  productId: string;
  quantity: number;
  unitPrice: number;
}

export interface CheckoutRequest {
  items: CheckoutLineRequest[];
  shippingAddressId: string;
}

/* ---------------------------------------------------------------- reviews */

export type ReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface Review {
  id: string;
  productId: string;
  rating: number;
  title: string;
  body: string;
  status: ReviewStatus;
  verifiedPurchase: boolean; // computed server-side — never client-side
  createdAt: string;
}

export interface SubmitReviewRequest {
  productId: string;
  rating: number;
  title: string;
  body: string;
}

/** Moderation queue item. review-service has no productName field - the
 *  admin screen resolves it from catalog-service, same pattern as OrderItem. */
export type ModerationItem = Review;

/* ---------------------------------------------------------------- profile */

export interface Address {
  id: string;
  label: string; // "Home" / "Work"
  line: string; // single display line, formatted server-side
  isDefault: boolean;
}

export interface CreateAddressRequest {
  label: string;
  line1: string;
  line2?: string;
  city: string;
  stateProvince?: string;
  postalCode: string;
  countryCode: string;
}

/** /api/v1/users/me response. roles/addresses are only ever populated for
 *  the CALLER'S OWN profile (see UserResponse.from javadoc backend-side). */
export interface UserProfile {
  id: string;
  email: string; // read-only in the UI - Keycloak owns identity
  firstName: string;
  lastName: string;
  phoneNumber: string | null;
  loyaltyTier: string; // e.g. "GOLD"
  createdAt: string;
  roles: string[]; // 'CUSTOMER' | 'VENDOR' | 'ADMIN' — drives menu + guards
  addresses: Address[];
}

export interface ProfileUpdate {
  firstName: string;
  lastName: string;
  phoneNumber: string;
}
