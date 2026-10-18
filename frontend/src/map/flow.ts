// Movement of the flow along the new network: a dash pattern that runs from the tie-ins towards the OKS
// (segments are directed that way). Only the variant layer is animated, a few frames per second are enough.

import {map} from './engine';
import {anim} from './anim';

const DASHES = [
  [0, 4, 3], [0.5, 4, 2.5], [1, 4, 2], [1.5, 4, 1.5], [2, 4, 1], [2.5, 4, 0.5], [3, 4, 0],
  [0, 0.5, 3, 3.5], [0, 1, 3, 3], [0, 1.5, 3, 2.5], [0, 2, 3, 2], [0, 2.5, 3, 1.5], [0, 3, 3, 1], [0, 3.5, 3, 0.5],
];
let timer: ReturnType<typeof setInterval> | 0 = 0;
let step = 0;
let paused = false;

function frame() {
  if (!map.getLayer('res-flow-anim') || paused || document.hidden) return;
  step = (step + 1) % DASHES.length;
  map.setPaintProperty('res-flow-anim', 'line-dasharray', DASHES[step]);
}

export function syncFlow() {
  if (!map) return;
  const on = anim.flow && !!map.getLayer('res-flow-anim') && !paused;
  if (map.getLayer('res-flow-anim')) map.setLayoutProperty('res-flow-anim', 'visibility', on ? 'visible' : 'none');
  if (timer) clearInterval(timer);
  timer = on ? setInterval(frame, 80) : 0;
}

/** The flow pauses while something else is replayed over the map. */
export function pauseFlow(p: boolean) {
  paused = p;
  syncFlow();
}
