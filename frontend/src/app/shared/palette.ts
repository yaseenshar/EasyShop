/**
 * Deterministic per-product hue, shared by <app-product-art> (the generated
 * placeholder tile) and the Shop page's "Color" filter. There is no color
 * attribute on Product server-side, so a real color swatch filter isn't
 * possible - instead the swatches filter by the SAME hue that's already
 * painted on the product's card, so "filter by blue" genuinely narrows the
 * grid to the products whose visible art is blue, rather than a control that
 * does nothing.
 */
export function hueForSeed(seed: string): number {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) {
    hash = (hash << 5) - hash + seed.charCodeAt(i);
    hash |= 0;
  }
  return Math.abs(hash) % 360;
}

export interface ColorBucket {
  key: string;
  name: string;
  swatch: string;
  hueMin: number;
  hueMax: number;
}

/** Six buckets spanning the hue wheel; swatch is a fixed representative color. */
export const COLOR_BUCKETS: ColorBucket[] = [
  { key: 'red', name: 'Red', swatch: 'oklch(58% 0.22 25)', hueMin: 340, hueMax: 360 },
  { key: 'red2', name: 'Red', swatch: 'oklch(58% 0.22 25)', hueMin: 0, hueMax: 20 },
  { key: 'orange', name: 'Orange', swatch: 'oklch(68% 0.17 55)', hueMin: 20, hueMax: 80 },
  { key: 'green', name: 'Green', swatch: 'oklch(65% 0.17 145)', hueMin: 80, hueMax: 170 },
  { key: 'blue', name: 'Blue', swatch: 'oklch(55% 0.2 260)', hueMin: 170, hueMax: 260 },
  { key: 'purple', name: 'Purple', swatch: 'oklch(50% 0.2 300)', hueMin: 260, hueMax: 320 },
  { key: 'pink', name: 'Pink', swatch: 'oklch(65% 0.2 340)', hueMin: 320, hueMax: 340 },
];

/** Collapses the two red wrap-around buckets into one filter option. */
export const COLOR_FILTERS = [
  { key: 'red', name: 'Red', swatch: 'oklch(58% 0.22 25)' },
  { key: 'orange', name: 'Orange', swatch: 'oklch(68% 0.17 55)' },
  { key: 'green', name: 'Green', swatch: 'oklch(65% 0.17 145)' },
  { key: 'blue', name: 'Blue', swatch: 'oklch(55% 0.2 260)' },
  { key: 'purple', name: 'Purple', swatch: 'oklch(50% 0.2 300)' },
  { key: 'pink', name: 'Pink', swatch: 'oklch(65% 0.2 340)' },
];

export function colorKeyForSeed(seed: string): string {
  const hue = hueForSeed(seed);
  const bucket = COLOR_BUCKETS.find((b) => hue >= b.hueMin && hue < b.hueMax);
  return bucket?.key === 'red2' ? 'red' : (bucket?.key ?? 'blue');
}
