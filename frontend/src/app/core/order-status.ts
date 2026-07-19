import { SagaStatus } from './api-types';

/**
 * The design's order timeline shows THREE customer-facing steps:
 *   Placed → Processing → Confirmed        (happy path)
 *   Placed → Processing → Cancelled        (failure path)
 *
 * The backend saga has EIGHT states (§3.2). This mapper is the deliberate
 * projection between the two vocabularies — the customer sees a promise, the
 * operator sees a state machine. Keep the raw SagaStatus available on the
 * order detail (e.g. a subtle sub-label or title attribute): "Processing —
 * CHARGING_PAYMENT" is exactly the demo moment that shows the saga through
 * the UI.
 *
 * COMPENSATING intentionally maps to PROCESSING, not CANCELLED: compensation
 * is in flight, the terminal answer isn't known yet, and the UI must not
 * announce outcomes the orchestrator hasn't committed.
 */
export type DisplayStatus = 'PROCESSING' | 'CONFIRMED' | 'CANCELLED';

const TERMINAL: ReadonlySet<SagaStatus> = new Set<SagaStatus>(['CONFIRMED', 'CANCELLED']);

export function isTerminal(status: SagaStatus): boolean {
  return TERMINAL.has(status);
}

export function toDisplayStatus(status: SagaStatus): DisplayStatus {
  if (status === 'CONFIRMED') return 'CONFIRMED';
  if (status === 'CANCELLED') return 'CANCELLED';
  return 'PROCESSING';
}

/** Timeline steps exactly as the design renders them (labels + which dots
 *  are "done"); colors come from app.css, not from here. */
export interface TimelineStep {
  label: 'Placed' | 'Processing' | 'Confirmed' | 'Cancelled';
  state: 'done' | 'pending' | 'failed';
}

export function timelineFor(status: SagaStatus): TimelineStep[] {
  const display = toDisplayStatus(status);
  if (display === 'CANCELLED') {
    return [
      { label: 'Placed', state: 'done' },
      { label: 'Processing', state: 'done' },
      { label: 'Cancelled', state: 'failed' },
    ];
  }
  return [
    { label: 'Placed', state: 'done' },
    { label: 'Processing', state: 'done' },
    { label: 'Confirmed', state: display === 'CONFIRMED' ? 'done' : 'pending' },
  ];
}

/**
 * Polling policy for the order-status screen. Checkout returns before the
 * saga completes (§4.6/§4.7 — outbox, Kafka, eventual consistency); the UI
 * polls GET /api/v1/orders/{id} until a terminal state.
 *
 * Backoff: 1s, 2s, 3s, then 5s cap. Stop polling after STALL_AFTER_MS
 * without a terminal state and switch the screen to its "delayed" copy —
 * this is the UI face of the DELIBERATE §3.2 decision that a stock-confirm
 * failure after successful payment is NOT auto-compensated but paused for
 * human review. Suggested copy, in the interface's voice:
 *
 *   "This order is taking longer than expected. Your payment is safe — we'll
 *    email you as soon as it's confirmed."
 *
 * (notification-service sends that email; the promise is real.)
 */
export const STALL_AFTER_MS = 120_000;

export function pollDelayMs(attempt: number): number {
  const schedule = [1_000, 2_000, 3_000];
  return schedule[attempt] ?? 5_000;
}
