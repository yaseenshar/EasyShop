import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { hueForSeed } from './palette';

/**
 * catalog-service's Product has no image field (there is no product-photo
 * pipeline in this system) — every screen that would show a product photo
 * shows this instead: a deterministic gradient tile (hashed from the
 * product's sku/id, so the same product always renders the same art) with a
 * centered bag glyph echoing the EasyShop mark. Fills its container; the
 * caller controls size/aspect-ratio via CSS on the wrapping element.
 */
@Component({
  selector: 'app-product-art',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="product-art" [style.background]="gradient()" [attr.aria-label]="label()" role="img">
      <svg class="glyph" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4">
        <path
          stroke-linecap="round"
          stroke-linejoin="round"
          d="M16 11V7a4 4 0 00-8 0v4M5 9h14l1 12H4L5 9z"
        />
      </svg>
    </div>
  `,
  styles: [
    `
      :host { display: block; width: 100%; height: 100%; }
      .product-art {
        width: 100%;
        height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        overflow: hidden;
      }
      .glyph {
        width: 34%;
        height: 34%;
        color: color-mix(in oklch, white 55%, transparent);
      }
      :root.dark .glyph { color: color-mix(in oklch, black 30%, transparent); }
    `,
  ],
})
export class ProductArt {
  readonly seed = input.required<string>();
  readonly name = input<string>('');

  protected readonly label = computed(() => this.name() || 'Product');

  protected readonly gradient = computed(() => {
    const hue = hueForSeed(this.seed());
    const hue2 = (hue + 34) % 360;
    return `linear-gradient(135deg, oklch(74% 0.11 ${hue}) 0%, oklch(88% 0.08 ${hue2}) 100%)`;
  });
}
