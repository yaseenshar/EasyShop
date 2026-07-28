import { Injectable, computed, signal } from '@angular/core';

const STORAGE_KEY = 'easyshop.wishlist';

/**
 * Wishlist state. There is no backend wishlist concept (no endpoint, no
 * table) - this is deliberately local-only, persisted the same way
 * ThemeService persists the dark-mode choice, and synced across tabs via the
 * `storage` event so the header badge and the wishlist page never disagree.
 */
@Injectable({ providedIn: 'root' })
export class WishlistService {
  private readonly ids = signal<Set<string>>(this.load());
  readonly items = this.ids.asReadonly();
  readonly count = computed(() => this.ids().size);

  constructor() {
    window.addEventListener('storage', (e) => {
      if (e.key === STORAGE_KEY) this.ids.set(this.load());
    });
  }

  has(productId: string): boolean {
    return this.ids().has(productId);
  }

  toggle(productId: string): void {
    this.ids.update((set) => {
      const next = new Set(set);
      next.has(productId) ? next.delete(productId) : next.add(productId);
      this.persist(next);
      return next;
    });
  }

  remove(productId: string): void {
    this.ids.update((set) => {
      if (!set.has(productId)) return set;
      const next = new Set(set);
      next.delete(productId);
      this.persist(next);
      return next;
    });
  }

  private load(): Set<string> {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      return raw ? new Set(JSON.parse(raw) as string[]) : new Set();
    } catch {
      return new Set();
    }
  }

  private persist(set: Set<string>): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify([...set]));
    } catch {
      // private browsing - wishlist just won't persist
    }
  }
}
