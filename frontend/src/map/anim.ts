// What is animated and how fast: one set of settings for all animations. The system setting "reduce motion" turns
// animations off by default.

import {reactive} from 'vue';
import {prefs} from '@/lib/prefs';

const reduced = typeof window !== 'undefined' && window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

export const ANIM_DEFAULTS = {
  build: !reduced,        // replay of the construction (while calculating and on request)
  live: !reduced,         // follow the construction while the calculation runs
  transitions: !reduced,  // switching variants
  flow: false,            // movement of the flow along the new network
  speed: 1,               // replay speed
  transitionMs: 700,      // switching variants, ms
};

export const reducedMotion = reduced;
export const anim = reactive(Object.assign({}, ANIM_DEFAULTS, prefs.get('anim', {})));

export function setAnim<K extends keyof typeof ANIM_DEFAULTS>(key: K, value: (typeof ANIM_DEFAULTS)[K]) {
  (anim as any)[key] = value;
  prefs.set('anim', {...anim});
}

export function resetAnim() {
  Object.assign(anim, ANIM_DEFAULTS);
  prefs.set('anim', {...anim});
}
