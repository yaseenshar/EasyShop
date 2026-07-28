import { Injectable, effect, signal } from '@angular/core';

/**
 * Dark mode. Persists the choice and reflects it as a `.dark` class on
 * <html> (styles.css keys every token off `.dark`, so no component needs to
 * know the mode). Defaults to the OS preference on first visit.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly isDark = signal<boolean>(this.initial());

  constructor() {
    effect(() => {
      const dark = this.isDark();
      document.documentElement.classList.toggle('dark', dark);
      try {
        localStorage.setItem('easyshop.theme', dark ? 'dark' : 'light');
      } catch {
        // private browsing - theme just won't persist
      }
    });
  }

  toggle(): void {
    this.isDark.update((v) => !v);
  }

  private initial(): boolean {
    try {
      const saved = localStorage.getItem('easyshop.theme');
      if (saved) return saved === 'dark';
    } catch {
      // ignore
    }
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
  }
}
