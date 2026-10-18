// Drawing a polygon on the map (an edit of the input): click — a vertex, click on the first vertex, double click
// or Enter — finish, Backspace — remove the last vertex, Esc — cancel.

import {ref} from 'vue';
import {addLayer, captureClicks, hint, map, removeLayers, setData} from './engine';

type Any = any;
let active: {points: number[][]; cursor?: number[]; onFinish?: (g: Any) => void; onCancel?: () => void} | null = null;
export const drawing = ref(false);
export const drawPoints = ref(0);

export function startDraw({onFinish, onCancel}: {onFinish?: (g: Any) => void; onCancel?: () => void} = {}) {
  cancelDraw();
  active = {points: [], onFinish, onCancel};
  drawing.value = true;
  drawPoints.value = 0;
  map.doubleClickZoom.disable();
  render();
  hint('Щелчок — вершина · щелчок по первой вершине или Enter — готово · Backspace — убрать вершину · Esc — отмена', {keep: true});
  captureClicks((e) => {
    const pts = active!.points;
    const p = [e.lngLat.lng, e.lngLat.lat];
    if (pts.length >= 3) {
      const first = map.project(pts[0] as [number, number]);
      if (Math.hypot(first.x - e.point.x, first.y - e.point.y) < 10) {
        finish();
        return;
      }
    }
    pts.push(p);
    drawPoints.value = pts.length;
    render();
  });
  map.on('dblclick', onDbl);
  map.on('mousemove', onMove);
  document.addEventListener('keydown', onKey);
}

function onDbl(e: Any) {
  if (!active) return;
  e.preventDefault();
  if (active.points.length >= 3) finish();
}

function onMove(e: Any) {
  if (!active) return;
  active.cursor = [e.lngLat.lng, e.lngLat.lat];
  render();
}

function onKey(e: KeyboardEvent) {
  if (!active) return;
  if (e.key === 'Escape') cancelDraw(true);
  else if (e.key === 'Enter' && active.points.length >= 3) finish();
  else if (e.key === 'Backspace' && active.points.length) {
    active.points.pop();
    drawPoints.value = active.points.length;
    render();
  }
}

/** Finish from a button (touch screens have no Enter). */
export function finishDraw() {
  if (active && active.points.length >= 3) finish();
}

function render() {
  if (!active) return;
  const pts = active.points;
  const line = pts.concat(active.cursor ? [active.cursor] : []);
  const features: Any[] = [];
  if (line.length >= 2) features.push({type: 'Feature', properties: {}, geometry: {type: 'LineString', coordinates: line}});
  if (pts.length >= 3) features.push({type: 'Feature', properties: {}, geometry: {type: 'Polygon', coordinates: [pts.concat([pts[0]])]}});
  pts.forEach((p, i) => features.push({type: 'Feature', properties: {first: i === 0}, geometry: {type: 'Point', coordinates: p}}));
  setData('draw', {type: 'FeatureCollection', features});
  if (!map.getLayer('draw-fill')) {
    addLayer({id: 'draw-fill', type: 'fill', source: 'draw', filter: ['==', ['geometry-type'], 'Polygon'], paint: {'fill-color': '#e11d48', 'fill-opacity': 0.2}});
    addLayer({id: 'draw-line', type: 'line', source: 'draw', filter: ['==', ['geometry-type'], 'LineString'], paint: {'line-color': '#be123c', 'line-width': 2, 'line-dasharray': [2, 1]}});
    addLayer({id: 'draw-pt', type: 'circle', source: 'draw', filter: ['==', ['geometry-type'], 'Point'],
      paint: {'circle-radius': ['case', ['get', 'first'], 6, 4], 'circle-color': '#fff', 'circle-stroke-color': '#be123c', 'circle-stroke-width': 2}});
  }
}

function finish() {
  const pts = active!.points;
  const done = active!.onFinish;
  stop();
  if (done) done({type: 'Polygon', coordinates: [pts.concat([pts[0]])]});
}

export function cancelDraw(user = false) {
  if (!active) return;
  const cb = active.onCancel;
  stop();
  if (user && cb) cb();
}

function stop() {
  active = null;
  drawing.value = false;
  captureClicks(null);
  map.off('dblclick', onDbl);
  map.off('mousemove', onMove);
  document.removeEventListener('keydown', onKey);
  setTimeout(() => map.doubleClickZoom.enable(), 300);
  removeLayers('draw-');
  setTimeout(() => { if (map.getSource('draw') && !map.getLayer('draw-fill')) map.removeSource('draw'); }, 0);
  hint('');
}

/** Shows polygons of pending edits (not yet saved as a version). */
export function showPending(features: Any[]) {
  setData('pending', {type: 'FeatureCollection', features});
  if (!map.getLayer('pending-fill')) {
    addLayer({id: 'pending-fill', type: 'fill', source: 'pending', paint: {'fill-color': '#e11d48', 'fill-opacity': 0.15}});
    addLayer({id: 'pending-line', type: 'line', source: 'pending', paint: {'line-color': '#be123c', 'line-width': 2, 'line-dasharray': [1, 1]}});
  }
}
