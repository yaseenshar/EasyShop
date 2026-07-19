import { Injectable, signal } from '@angular/core';

/** Single-slot toast (matches the design: one message at a time, bottom-right, auto-dismiss). */
@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly message = signal<string | null>(null);
  private timer?: ReturnType<typeof setTimeout>;

  show(text: string, durationMs = 2200): void {
    this.message.set(text);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.message.set(null), durationMs);
  }
}
