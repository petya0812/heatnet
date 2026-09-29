// Drawing a polygon on the map (an edit of the input): click — a vertex, click on the first vertex, double click
// or Enter — finish, Backspace — remove the last vertex, Esc — cancel.

import {addLayer, captureClicks, hint, map, removeLayers, setData} from './map.js';

let active = null;

export function isDrawing() {
  return !!active;
}

export function startDraw({onFinish, onCancel} = {}) {
  cancelDraw();
  active = {points: [], onFinish, onCancel};
  map.doubleClickZoom.disable();
  render();
  hint('Нарисуйте контур: щелчок — вершина, щелчок по первой вершине или Enter — готово, Esc — отмена', {keep: true});
  captureClicks((e) => {
    const pts = active.points;
    const p = [e.lngLat.lng, e.lngLat.lat];
    if (pts.length >= 3) {
      const first = map.project(pts[0]);
      if (Math.hypot(first.x - e.point.x, first.y - e.point.y) < 10) {
        finish();
        return;
      }
    }
    pts.push(p);
    render();
  });
  map.on('dblclick', onDbl);
  map.on('mousemove', onMove);
  document.addEventListener('keydown', onKey);
}

function onDbl(e) {
  if (!active) return;
  e.preventDefault();
  if (active.points.length >= 3) finish();
}

function onMove(e) {
  if (!active) return;
  active.cursor = [e.lngLat.lng, e.lngLat.lat];
  render();
}

function onKey(e) {
  if (!active) return;
  if (e.key === 'Escape') cancelDraw(true);
  else if (e.key === 'Enter' && active.points.length >= 3) finish();
  else if (e.key === 'Backspace' && active.points.length) {
    active.points.pop();
    render();
  }
}

function render() {
  if (!active) return;
  const pts = active.points;
  const line = pts.concat(active.cursor ? [active.cursor] : []);
  const features = [];
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
  const pts = active.points;
  const done = active.onFinish;
  stop();
  if (done) done({type: 'Polygon', coordinates: [pts.concat([pts[0]])]});
}

export function cancelDraw(user) {
  if (!active) return;
  const cb = active.onCancel;
  stop();
  if (user && cb) cb();
}

function stop() {
  active = null;
  captureClicks(null);
  map.off('dblclick', onDbl);
  map.off('mousemove', onMove);
  document.removeEventListener('keydown', onKey);
  setTimeout(() => map.doubleClickZoom.enable(), 300);
  removeLayers('draw-');
  if (map.getSource('draw')) map.removeSource('draw');
  hint('');
}

/** Shows polygons of pending edits (not yet saved as a version). */
export function showPending(features) {
  setData('pending', {type: 'FeatureCollection', features});
  if (!map.getLayer('pending-fill')) {
    addLayer({id: 'pending-fill', type: 'fill', source: 'pending', paint: {'fill-color': '#e11d48', 'fill-opacity': 0.15}});
    addLayer({id: 'pending-line', type: 'line', source: 'pending', paint: {'line-color': '#be123c', 'line-width': 2, 'line-dasharray': [1, 1]}});
  }
}
