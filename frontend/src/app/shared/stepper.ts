import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

export type StepState = 'done' | 'active' | 'pending' | 'failed';
export interface StepperStep {
  label: string;
  state: StepState;
}

/**
 * Numbered-circle-with-connecting-line stepper (the mockup's checkout
 * stepper AND its order-timeline use the exact same visual — this is one
 * component so both stay identical instead of drifting, per the mockup's
 * "1 → 2 → 3" numbered circles, not plain dots).
 */
@Component({
  selector: 'app-stepper',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="stepper">
      <div class="step-line">
        <div class="step-line-fill" [style.width.%]="fillPercent()"></div>
      </div>
      @for (step of steps(); track step.label; let i = $index) {
        <div class="step">
          <div class="step-dot" [class]="'step-dot-' + step.state">
            @if (step.state === 'done') {
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
              </svg>
            } @else if (step.state === 'failed') {
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            } @else {
              {{ i + 1 }}
            }
          </div>
          <span class="step-label" [class]="'step-label-' + step.state">{{ step.label }}</span>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .stepper {
        position: relative;
        display: flex;
        justify-content: space-between;
        gap: 8px;
        margin-bottom: 36px;
      }
      .step-line {
        position: absolute;
        left: 18px;
        right: 18px;
        top: 18px;
        height: 3px;
        background: var(--timeline-idle);
        z-index: 0;
        border-radius: 2px;
        overflow: hidden;
      }
      .step-line-fill {
        height: 100%;
        background: var(--success);
        transition: width 0.3s ease;
      }
      .step {
        position: relative;
        z-index: 1;
        flex: 1;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 8px;
      }
      .step-dot {
        width: 36px;
        height: 36px;
        border-radius: 50%;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 14px;
        font-weight: 800;
        background: var(--surface);
        border: 2px solid var(--timeline-idle);
        color: var(--timeline-idle-text);
      }
      .step-dot svg { width: 18px; height: 18px; }
      .step-dot-active {
        background: var(--primary);
        border-color: var(--primary);
        color: #fff;
        box-shadow: var(--shadow-glow-primary);
      }
      .step-dot-done { background: var(--success); border-color: var(--success); color: #fff; }
      .step-dot-failed { background: var(--danger); border-color: var(--danger); color: #fff; }
      .step-dot-pending { background: var(--surface); }

      .step-label { font-size: 12px; font-weight: 600; color: var(--timeline-idle-text); text-align: center; }
      .step-label-active,
      .step-label-done { color: var(--ink); font-weight: 700; }
      .step-label-failed { color: var(--danger); font-weight: 700; }
    `,
  ],
})
export class Stepper {
  readonly steps = input.required<StepperStep[]>();

  protected readonly fillPercent = computed(() => {
    const list = this.steps();
    if (list.length <= 1) return 0;
    const activeIndex = list.findIndex((s) => s.state === 'active');
    const doneCount = list.filter((s) => s.state === 'done').length;
    const failedIndex = list.findIndex((s) => s.state === 'failed');
    const progressIndex = failedIndex >= 0 ? failedIndex : activeIndex >= 0 ? activeIndex : doneCount;
    return Math.min(100, (progressIndex / (list.length - 1)) * 100);
  });
}
