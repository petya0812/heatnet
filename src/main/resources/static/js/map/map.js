// The map: input layers of a dataset (vector tiles), layers of a variant (GeoJSON), highlight, popups, overlays of
// other modules (comparison, replay, search trace) and drawing of a polygon for an edit of the input.
// Other modules never touch the layers of each other: each owns a prefix and asks this module to add or remove it.

import {esc} from '../core/dom.js';
import {fmt} from '../core/format.js';
import {prefs} from '../core/store.js';

/* global maplibregl */

export const DN_STOPS = [50, '#fde68a', 100, '#fbbf24', 200, '#f97316', 400, '#dc2626', 700, '#9f1239', 1200, '#4c0519'];
export const dnColor = ['interpolate', ['linear'], ['coalesce', ['to-number', ['get', 'diameter']], 50], ...DN_STOPS];
export const dnWidth = (base) => ['interpolate', ['linear'], ['zoom'], 12, base * 0.5, 16, base * 1.6, 19, base * 3];

export const RESTRICTION_COLORS = {
  road: '#b8bcc2', tram_tracks: '#a78bfa', railway: '#8b6f47', water: '#7dd3fc', park: '#86efac',
  social_area: '#f9a8d4', prohibited_site: '#fca5a5', gas_pipeline: '#eab308', power_cable: '#7c3aed',
  heat_network: '#64748b', oks: '#d6d0c4', oks_existing: '#d6d0c4', building: '#d6d0c4',
};
const restrictionColor = ['match', ['get', 'restriction_type'], ...Object.entries(RESTRICTION_COLORS).flat(), '#d6d3d1'];

export const map = new maplibregl.Map({
  container: 'map',
  style: {
    version: 8,
    sources: {
      osm: {type: 'raster', tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'], tileSize: 256, maxzoom: 19,
        attribution: '© участники OpenStreetMap'},
    },
    layers: [
      {id: 'bg', type: 'background', paint: {'background-color': '#f2f0ea'}},
      {id: 'osm', type: 'raster', source: 'osm', paint: {'raster-opacity': 0.55, 'raster-saturation': -0.6}, layout: {visibility: 'none'}},
    ],
  },
  center: [37.62, 55.75],
  zoom: 10,
  attributionControl: {compact: true},
  doubleClickZoom: true,
});
map.addControl(new maplibregl.NavigationControl({showCompass: false}), 'bottom-right');
map.addControl(new maplibregl.ScaleControl({unit: 'metric'}), 'bottom-left');

/** Layers can be added once the style is parsed (a hidden tab draws no frames, so no waiting for one). */
let styleReady = false;
export const ready = new Promise((res) => {
  const done = () => {
    styleReady = true;
    res();
  };
  if (map.isStyleLoaded()) done();
  map.once('style.load', done);
  map.once('load', done);
});

/** Changes of the style asked for before it is ready are applied once it is (the page never waits for the map). */
const whenReady = (fn) => (...args) => (styleReady ? fn(...args) : ready.then(() => fn(...args)));

// ---------------------------------------------------------------- layer groups (legend)

/** Groups of the legend: id → {title, layers, on}. Visibility is a per-viewer convenience. */
export const groups = {};
const visible = prefs.get('layers', {});
let legendRender = () => {};

export function onLegendChange(fn) {
  legendRender = fn;
}

/** Layers are declared in batches; the legend is rebuilt once per frame, not once per group. */
let legendPending = false;
const scheduleLegend = () => {
  if (legendPending) return;
  legendPending = true;
  requestAnimationFrame(() => {
    legendPending = false;
    applyVisibility();
    legendRender();
  });
};

export function defineGroup(id, spec) {
  groups[id] = Object.assign({on: true}, spec);
  if (!(id in visible)) visible[id] = groups[id].on;
  scheduleLegend();
}

export function dropGroups(prefix) {
  for (const id of Object.keys(groups)) if (id.startsWith(prefix)) delete groups[id];
  scheduleLegend();
}

export function isVisible(id) {
  return !!visible[id];
}

export function setVisible(id, on) {
  visible[id] = on;
  prefs.set('layers', visible);
  applyVisibility();
  legendRender();
}

function applyVisibilityNow() {
  for (const [id, g] of Object.entries(groups)) {
    for (const l of g.layers) {
      if (map.getLayer(l)) map.setLayoutProperty(l, 'visibility', visible[id] ? 'visible' : 'none');
    }
  }
}

// ---------------------------------------------------------------- helpers

function removeLayersNow(prefix) {
  if (!map.getStyle()) return;
  for (const l of map.getStyle().layers.slice()) {
    if (l.id.startsWith(prefix)) map.removeLayer(l.id);
  }
}

function removeSourcesNow(prefix) {
  for (const id of Object.keys(map.getStyle().sources)) {
    if (id.startsWith(prefix) && !map.getStyle().layers.some((l) => l.source === id)) map.removeSource(id);
  }
}

function setDataNow(id, data, opts = {}) {
  const src = map.getSource(id);
  if (src) src.setData(data);
  else map.addSource(id, Object.assign({type: 'geojson', data}, opts));
}

function addLayerNow(spec, before) {
  if (map.getLayer(spec.id)) map.removeLayer(spec.id);
  map.addLayer(spec, before && map.getLayer(before) ? before : undefined);
}

const firstOf = (prefixes) => {
  const l = map.getStyle().layers.find((x) => prefixes.some((p) => x.id.startsWith(p)));
  return l ? l.id : undefined;
};

export function bboxOf(features) {
  const b = [Infinity, Infinity, -Infinity, -Infinity];
  const walk = (c) => {
    if (typeof c[0] === 'number') {
      b[0] = Math.min(b[0], c[0]); b[1] = Math.min(b[1], c[1]); b[2] = Math.max(b[2], c[0]); b[3] = Math.max(b[3], c[1]);
    } else c.forEach(walk);
  };
  for (const f of features) if (f && f.geometry) walk(f.geometry.coordinates);
  return b[0] === Infinity ? null : b;
}

export function padding(base = 60) {
  const legend = document.getElementById('legend');
  const player = document.getElementById('player');
  const lw = legend && !legend.classList.contains('collapsed') && map.getContainer().clientWidth > 800 ? legend.offsetWidth + 10 : 0;
  const ph = player && !player.hidden ? player.offsetHeight + 20 : 0;
  return {top: base, left: base, right: base + lw, bottom: base + ph};
}

function fitNow(b, {maxZoom = 17, duration = 500} = {}) {
  if (!b) return;
  map.fitBounds([[b[0], b[1]], [b[2], b[3]]], {padding: padding(), maxZoom, duration});
}

// ---------------------------------------------------------------- input layers

let datasetShown = null;

/** Shows the input layers of a dataset; {@code stamp} (time the data was built) renews cached tiles after a rebuild. */
export async function showDataset(id, {fitTo = true, stamp = ''} = {}) {
  await ready;
  const key = id ? id + '|' + stamp : null;
  if (datasetShown === key) return;
  const sameDataset = datasetShown && id && datasetShown.startsWith(id + '|');
  datasetShown = key;
  removeLayers('in-');
  if (map.getSource('ds')) map.removeSource('ds');
  if (!id) return;
  if (sameDataset) fitTo = false;
  map.addSource('ds', {type: 'vector', minzoom: 9, maxzoom: 16,
    tiles: [location.origin + '/api/datasets/' + id + '/tiles/{z}/{x}/{y}.mvt' + (stamp ? '?t=' + encodeURIComponent(stamp) : '')]});
  const before = firstOf(['res-', 'cmp-', 'tr-', 'pl-', 'hl-', 'draw-']);
  const add = (l) => map.addLayer(Object.assign({source: 'ds'}, l), before);
  const notEdit = ['!=', ['get', 'source'], 'edit'];
  add({id: 'in-restr-fill', type: 'fill', 'source-layer': 'restrictions', filter: ['all', ['==', ['geometry-type'], 'Polygon'], notEdit],
    paint: {'fill-color': restrictionColor, 'fill-opacity': 0.45}});
  add({id: 'in-restr-line', type: 'line', 'source-layer': 'restrictions', filter: ['==', ['geometry-type'], 'LineString'],
    paint: {'line-color': restrictionColor, 'line-width': 2, 'line-dasharray': [3, 2]}});
  add({id: 'in-restr-point', type: 'circle', 'source-layer': 'restrictions', filter: ['==', ['geometry-type'], 'Point'],
    paint: {'circle-color': restrictionColor, 'circle-radius': 3}});
  add({id: 'in-edits-fill', type: 'fill', 'source-layer': 'restrictions', filter: ['all', ['==', ['geometry-type'], 'Polygon'], ['==', ['get', 'source'], 'edit']],
    paint: {'fill-color': '#e11d48', 'fill-opacity': 0.22}});
  add({id: 'in-edits-line', type: 'line', 'source-layer': 'restrictions', filter: ['==', ['get', 'source'], 'edit'],
    paint: {'line-color': '#be123c', 'line-width': 2.5, 'line-dasharray': [2, 1]}});
  add({id: 'in-buildings', type: 'fill', 'source-layer': 'buildings', paint: {'fill-color': '#c9c4ba', 'fill-outline-color': '#a8a296'}});
  add({id: 'in-oks', type: 'fill', 'source-layer': 'oks', paint: {'fill-color': '#fb923c', 'fill-opacity': 0.35, 'fill-outline-color': '#c2410c'}});
  add({id: 'in-oks-line', type: 'line', 'source-layer': 'oks', paint: {'line-color': '#c2410c', 'line-width': 1.5}});
  add({id: 'in-network', type: 'line', 'source-layer': 'network', layout: {'line-cap': 'round'},
    paint: {'line-color': '#334155', 'line-width': ['interpolate', ['linear'], ['coalesce', ['get', 'diameter'], 100], 50, 1.5, 300, 3, 1000, 6], 'line-opacity': 0.85}});
  add({id: 'in-chambers', type: 'circle', 'source-layer': 'chambers',
    paint: {'circle-color': '#fff', 'circle-stroke-color': '#334155', 'circle-stroke-width': 1.5, 'circle-radius': 3.5}});
  add({id: 'in-cp', type: 'circle', 'source-layer': 'connection_points',
    paint: {'circle-color': '#c2410c', 'circle-radius': 4, 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5}});
  add({id: 'in-source', type: 'circle', 'source-layer': 'source',
    paint: {'circle-color': '#1d2327', 'circle-radius': 8, 'circle-stroke-color': '#fbbf24', 'circle-stroke-width': 3}});
  defineGroup('in-network', {section: 'before', title: 'Существующая сеть', hint: 'толщина — ДУ', layers: ['in-network'], sw: '#334155', kind: 'line'});
  defineGroup('in-chambers', {section: 'before', title: 'Существующие камеры', hint: 'при приближении', layers: ['in-chambers'], sw: '#fff', stroke: '#334155', kind: 'dot'});
  defineGroup('in-source', {section: 'before', title: 'Источник', layers: ['in-source'], sw: '#1d2327', stroke: '#fbbf24', kind: 'dot'});
  defineGroup('in-oks', {section: 'before', title: 'Перспективные ОКС', hint: 'контур и точка подключения', layers: ['in-oks', 'in-oks-line', 'in-cp'], sw: '#c2410c', kind: 'dot'});
  defineGroup('in-buildings', {section: 'before', title: 'Здания', hint: 'при приближении', layers: ['in-buildings'], sw: '#c9c4ba'});
  defineGroup('in-restr', {section: 'before', title: 'Ограничения', hint: 'при приближении', layers: ['in-restr-fill', 'in-restr-line', 'in-restr-point'], sw: '#b8bcc2', restrictions: true});
  defineGroup('in-edits', {section: 'before', title: 'Ограничения из правок', hint: 'добавлены вручную в этой версии', layers: ['in-edits-fill', 'in-edits-line'], sw: '#fecdd3', stroke: '#be123c'});
  applyVisibility();
  if (fitTo) {
    try {
      const b = await (await fetch('/api/datasets/' + id + '/bbox')).json();
      if (datasetShown === key) map.fitBounds([[b[0], b[1]], [b[2], b[3]]], {padding: padding(40), duration: 0});
    } catch (e) { /* no extent yet */ }
  }
}

// ---------------------------------------------------------------- variant layers

const RESULT_TYPES = ['heat_network', 'heat_chamber', 'technical_node', 'attachment', 'flow_change',
  'length_run', 'unconnected_oks'];
let variantShown = null;
let variantData = null;
let variantKeys = new Set();
let resultOpacity = 1;

const segKey = (f) => f.geometry.coordinates.map((c) => c[0].toFixed(6) + ',' + c[1].toFixed(6)).join(';');

/**
 * Shows a variant. With a transition, pieces common with the previous variant stay, the others fade out and in —
 * the difference is visible by eye.
 */
export async function showVariant(key, data, {transitionMs = 0} = {}) {
  await ready;
  const prev = variantData;
  const newKeys = new Set();
  for (const f of data.features) {
    if (f.properties.object_type === 'heat_network' && f.geometry) {
      const k = segKey(f);
      newKeys.add(k);
      f.properties._state = variantKeys.has(k) || !variantShown ? 'same' : 'new';
    }
  }
  const old = prev && prev.features && transitionMs > 0 && variantShown !== key
    ? prev.features.filter((f) => f.properties.object_type === 'heat_network' && f.geometry && !newKeys.has(segKey(f))) : [];
  variantShown = key;
  variantData = data;
  variantKeys = newKeys;
  setData('res', data);
  if (!map.getLayer('res-seg')) addResultLayers();
  const fade = transitionMs > 0 && (old.length || data.features.some((f) => f.properties._state === 'new'));
  setData('res-old', {type: 'FeatureCollection', features: old});
  if (!map.getLayer('res-old')) {
    addLayer({id: 'res-old', type: 'line', source: 'res-old', layout: {'line-cap': 'round', 'line-join': 'round'},
      paint: {'line-color': '#64748b', 'line-width': dnWidth(3.2), 'line-dasharray': [1.5, 1]}}, 'res-seg-casing');
  }
  const t = {duration: transitionMs, delay: 0};
  if (fade) {
    map.setPaintProperty('res-old', 'line-opacity-transition', {duration: 0});
    map.setPaintProperty('res-old', 'line-opacity', 0.9);
    map.setPaintProperty('res-seg-new', 'line-opacity-transition', {duration: 0});
    map.setPaintProperty('res-seg-new', 'line-opacity', 0);
    map.setPaintProperty('res-seg-new-casing', 'line-opacity', 0);
    requestAnimationFrame(() => requestAnimationFrame(() => {
      map.setPaintProperty('res-old', 'line-opacity-transition', t);
      map.setPaintProperty('res-old', 'line-opacity', 0);
      map.setPaintProperty('res-seg-new', 'line-opacity-transition', t);
      map.setPaintProperty('res-seg-new', 'line-opacity', resultOpacity);
      map.setPaintProperty('res-seg-new-casing', 'line-opacity-transition', t);
      map.setPaintProperty('res-seg-new-casing', 'line-opacity', resultOpacity);
    }));
  } else {
    map.setPaintProperty('res-old', 'line-opacity', 0);
    map.setPaintProperty('res-seg-new', 'line-opacity', resultOpacity);
    map.setPaintProperty('res-seg-new-casing', 'line-opacity', resultOpacity);
  }
  applyVisibility();
}

export function clearVariant() {
  if (!variantShown) return;
  variantShown = null;
  variantData = null;
  variantKeys = new Set();
  removeLayers('res-');
  removeSources('res');
  dropGroups('res-');
}

function addResultLayers() {
  const t = (type) => ['==', ['get', 'object_type'], type];
  const before = firstOf(['cmp-', 'tr-', 'pl-', 'hl-', 'draw-']);
  const add = (l) => addLayer(Object.assign({source: 'res'}, l), before);
  add({id: 'res-unconnected', type: 'circle', filter: ['all', t('unconnected_oks'), ['==', ['geometry-type'], 'Point']],
    paint: {'circle-color': '#dc2626', 'circle-radius': 10, 'circle-opacity': 0.35, 'circle-stroke-color': '#7f1d1d', 'circle-stroke-width': 2}});
  add({id: 'res-unconnected-area', type: 'fill', filter: ['all', t('unconnected_oks'), ['==', ['geometry-type'], 'Polygon']],
    paint: {'fill-color': '#dc2626', 'fill-opacity': 0.45, 'fill-outline-color': '#7f1d1d'}});
  add({id: 'res-flow-before', type: 'line', filter: t('flow_change'), layout: {'line-cap': 'round'},
    paint: {'line-color': '#94a3b8', 'line-width': ['interpolate', ['linear'], ['get', 'existing_flow_tph'], 0, 2, 100, 7, 1000, 14], 'line-opacity': 0}});
  add({id: 'res-flow', type: 'line', filter: t('flow_change'), layout: {'line-cap': 'round'},
    paint: {'line-color': ['interpolate', ['linear'], ['get', 'added_flow_tph'], 0, '#a5f3fc', 20, '#0891b2', 100, '#164e63'],
      'line-width': ['interpolate', ['linear'], ['get', 'calculated_flow_tph'], 0, 2, 100, 7, 1000, 14], 'line-opacity': 0.9}});
  add({id: 'res-short', type: 'line', filter: ['all', t('flow_change'), ['>', ['get', 'required_diameter'], ['get', 'existing_diameter']]],
    layout: {'line-cap': 'round'}, paint: {'line-color': '#d946ef', 'line-width': dnWidth(7), 'line-opacity': 0.55}});
  add({id: 'res-runs', type: 'line', filter: t('length_run'), layout: {'line-cap': 'round'},
    paint: {'line-color': ['step', ['get', 'usage'], '#16a34a', 0.8, '#eab308', 1.0001, '#dc2626'], 'line-width': dnWidth(9), 'line-opacity': 0.5}});
  const same = ['all', t('heat_network'), ['!=', ['get', '_state'], 'new']];
  const fresh = ['all', t('heat_network'), ['==', ['get', '_state'], 'new']];
  add({id: 'res-seg-casing', type: 'line', filter: same, layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': '#1d2327', 'line-width': dnWidth(4.6)}});
  add({id: 'res-seg', type: 'line', filter: same, layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': dnColor, 'line-width': dnWidth(3.2)}});
  add({id: 'res-seg-new-casing', type: 'line', filter: fresh, layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': '#1d2327', 'line-width': dnWidth(4.6)}});
  add({id: 'res-seg-new', type: 'line', filter: fresh, layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': dnColor, 'line-width': dnWidth(3.2)}});
  add({id: 'res-special', type: 'line', filter: ['all', t('heat_network'), ['==', ['get', 'laying_method'], 'special']],
    paint: {'line-color': '#ffffff', 'line-width': dnWidth(1.2), 'line-dasharray': [1.5, 1.5]}});
  add({id: 'res-flow-anim', type: 'line', filter: t('heat_network'), layout: {'line-cap': 'butt', visibility: 'none'},
    paint: {'line-color': '#ffffff', 'line-opacity': 0.85, 'line-width': ['interpolate', ['linear'], ['get', 'flow_tph'], 0, 1, 50, 2.5, 500, 5], 'line-dasharray': [0, 4, 3]}});
  add({id: 'res-tech', type: 'circle', filter: t('technical_node'), paint: {'circle-color': '#1d2327', 'circle-radius': 2.5}});
  add({id: 'res-chamber', type: 'circle', filter: t('heat_chamber'), paint: {'circle-color': '#2563eb', 'circle-radius': 5, 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5}});
  add({id: 'res-tie', type: 'circle', filter: t('attachment'), paint: {'circle-color': '#dc2626', 'circle-radius': 6, 'circle-stroke-color': '#fff', 'circle-stroke-width': 2}});
  defineGroup('res-seg', {section: 'after', title: 'Новые участки', hint: 'цвет — ДУ, мм · пунктир — спецпроход', layers: ['res-seg-casing', 'res-seg', 'res-seg-new-casing', 'res-seg-new', 'res-special', 'res-old'], sw: '#f97316', kind: 'line', ramp: true});
  defineGroup('res-tie', {section: 'after', title: 'Врезки', hint: 'новая камера на участке или врезка в камеру', layers: ['res-tie'], sw: '#dc2626', stroke: '#fff', kind: 'dot'});
  defineGroup('res-chamber', {section: 'after', title: 'Новые камеры', layers: ['res-chamber'], sw: '#2563eb', kind: 'dot'});
  defineGroup('res-tech', {section: 'after', title: 'Технические узлы', hint: 'смена способа прокладки', layers: ['res-tech'], sw: '#1d2327', kind: 'dot', on: false});
  defineGroup('res-runs', {section: 'after', title: 'Предельная длина', hint: 'доля от предела: зелёный < 80 %, жёлтый до 100 %, красный — превышен', layers: ['res-runs'], sw: '#16a34a', kind: 'line', on: true});
  defineGroup('res-flow', {section: 'change', title: 'Расход существующей сети', hint: 'толщина — расход участка, цвет — добавленный расход', layers: ['res-flow', 'res-flow-before'], sw: '#0891b2', kind: 'line', flowToggle: true});
  defineGroup('res-short', {section: 'change', title: 'Не хватит пропускной способности', hint: 'участки существующей сети, которым после подключения понадобится больший ДУ; в стоимость не входит', layers: ['res-short'], sw: '#d946ef', kind: 'line'});
  defineGroup('res-unconnected', {section: 'problems', title: 'ОКС не подключены', layers: ['res-unconnected', 'res-unconnected-area'], sw: '#fecaca', stroke: '#7f1d1d', kind: 'dot'});
  setResultOpacity(resultOpacity);
}

/** Dims the variant while something is replayed over it. */
function setResultOpacityNow(o) {
  resultOpacity = o;
  const lines = ['res-seg', 'res-seg-casing', 'res-seg-new', 'res-seg-new-casing', 'res-special', 'res-short', 'res-flow'];
  for (const l of lines) if (map.getLayer(l)) map.setPaintProperty(l, 'line-opacity', l === 'res-short' ? 0.55 * o : l === 'res-flow' ? 0.9 * o : o);
  for (const l of ['res-tie', 'res-chamber', 'res-tech']) {
    if (map.getLayer(l)) {
      map.setPaintProperty(l, 'circle-opacity', o);
      map.setPaintProperty(l, 'circle-stroke-opacity', o);
    }
  }
}

/** Existing network: flow before or after the new connections (crossfade). */
function showFlowAfterNow(after, ms = 400) {
  if (!map.getLayer('res-flow')) return;
  for (const [l, v] of [['res-flow', after ? 0.9 : 0], ['res-flow-before', after ? 0 : 0.9]]) {
    map.setPaintProperty(l, 'line-opacity-transition', {duration: ms});
    map.setPaintProperty(l, 'line-opacity', v * resultOpacity);
  }
}

export const hasVariant = () => !!variantShown;

// ---------------------------------------------------------------- highlight

function highlightNow(geojson, {fitTo = true, maxZoom = 17} = {}) {
  setData('hl', geojson);
  if (!map.getLayer('hl-line')) {
    // a halo under the objects of the variant: highlighted things keep their own colours
    addLayer({id: 'hl-fill', type: 'fill', source: 'hl', filter: ['==', ['geometry-type'], 'Polygon'], paint: {'fill-color': '#111827', 'fill-opacity': 0.18}});
    addLayer({id: 'hl-line', type: 'line', source: 'hl', layout: {'line-cap': 'round', 'line-join': 'round'},
      paint: {'line-color': '#111827', 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 9, 16, 20, 19, 34], 'line-opacity': 0.28, 'line-blur': 3}},
    map.getLayer('res-short') ? 'res-short' : undefined);
    addLayer({id: 'hl-point', type: 'circle', source: 'hl', filter: ['==', ['geometry-type'], 'Point'],
      paint: {'circle-color': 'rgba(0,0,0,0)', 'circle-radius': 16, 'circle-stroke-color': '#111827', 'circle-stroke-width': 3, 'circle-stroke-opacity': 0.8}});
  }
  if (fitTo) fit(bboxOf(geojson.features), {maxZoom});
}

function clearHighlightNow() {
  if (map.getSource('hl')) map.getSource('hl').setData({type: 'FeatureCollection', features: []});
}

// ---------------------------------------------------------------- popups

const LABELS = {
  diameter: 'ДУ, мм', flow_tph: 'Расход, т/ч', length: 'Длина, м', laying_method: 'Прокладка', cost: 'Стоимость, млн ₽',
  existing_object_id: 'Существующий участок', existing_diameter: 'Существующий ДУ', required_diameter: 'Требуемый ДУ',
  existing_flow_tph: 'Расход до, т/ч', added_flow_tph: 'Добавлено, т/ч', calculated_flow_tph: 'Расход после, т/ч',
  reconstructed: 'Не хватит пропускной способности', existing_chamber: 'Врезка в существующую камеру', measured_length: 'Учитываемая длина, м', max_length: 'Предел, м', usage: 'Использовано предела',
  oks_id: 'ОКС', reason_text: 'Причина', detail: 'Подробности', heat_load: 'Нагрузка, Гкал/ч', restriction_type: 'Тип ограничения',
  id: 'Идентификатор', comment: 'Комментарий', start_node_id: 'Начальный узел', end_node_id: 'Конечный узел', name: 'Название', k: 'Коэффициент',
};
const TITLES = {
  heat_network: 'Новый участок', attachment: 'Врезка', heat_chamber: 'Новая камера',
  technical_node: 'Технический узел', flow_change: 'Существующий участок: расход',
  length_run: 'Часть сети одного ДУ', unconnected_oks: 'ОКС не подключён',
};
/** What a click picks first when several objects overlap: points of the result, then its lines, then the input. */
const CLICK_PRIORITY = ['res-tie', 'res-chamber', 'res-tech', 'res-unconnected', 'res-unconnected-area', 'res-seg-new', 'res-seg', 'res-runs',
  'res-short', 'res-flow', 'in-cp', 'in-oks', 'in-source', 'in-chambers', 'in-network', 'in-edits-fill', 'in-restr-point', 'in-restr-line',
  'in-restr-fill', 'in-buildings'];
const LAYER_TITLES = {
  'in-network': 'Существующий участок', 'in-chambers': 'Существующая камера', 'in-source': 'Источник', 'in-oks': 'Перспективный ОКС',
  'in-cp': 'Точка подключения', 'in-buildings': 'Существующее здание', 'in-restr-fill': 'Ограничение', 'in-restr-line': 'Ограничение',
  'in-restr-point': 'Ограничение', 'in-edits-fill': 'Ограничение, добавленное вручную', 'in-edits-line': 'Ограничение, добавленное вручную',
};
const HIDE = new Set(['derived', 'reason', 'depth_start', 'depth_end', 'object_type', 'variant_id', '_state', 'source', 'segments', 'path_length', 'total_length']);
const ORDER = ['id', 'oks_id', 'reason_text', 'detail', 'restriction_type', 'comment', 'diameter', 'existing_diameter', 'required_diameter', 'flow_tph',
  'existing_flow_tph', 'added_flow_tph', 'calculated_flow_tph', 'length', 'measured_length', 'max_length', 'usage', 'laying_method', 'cost', 'reconstructed', 'existing_object_id'];

let restrictionTitles = {};
export function setRestrictionTitles(t) {
  restrictionTitles = t;
}

let popupActions = {};
export function onPopupAction(name, fn) {
  popupActions[name] = fn;
}

/** The value of a CSS token (for map layers whose colours must match the interface, e.g. the comparison). */
export const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

function popupHtml(features) {
  const seen = new Set();
  const parts = [];
  // обводка, спецпроход и прежняя линия — тот же участок новой сети, что и сама линия
  const layerOf = (f) => f.layer.id.replace(/-casing$/, '').replace(/^res-(special|old)$/, 'res-seg');
  const rank = (f) => { const i = CLICK_PRIORITY.indexOf(layerOf(f)); return i < 0 ? CLICK_PRIORITY.length : i; };
  features = features.slice().sort((a, b) => rank(a) - rank(b));
  for (const f of features) {
    const p = f.properties || {};
    const lid = layerOf(f);
    if (['in-oks-line', 'in-edits-line', 'res-flow-anim', 'res-flow-before'].includes(lid)) continue;
    const key = lid.replace(/-new$/, '') + '|' + (p.id || f.id || JSON.stringify(p));
    if (seen.has(key)) continue;
    seen.add(key);
    const title = lid.startsWith('in-') ? LAYER_TITLES[lid] || 'Объект' : TITLES[p.object_type] || 'Объект';
    const rank = (k) => { const i = ORDER.indexOf(k); return i < 0 ? ORDER.length : i; };
    const rows = Object.entries(Object.assign(f.id != null && !p.id ? {id: f.id} : {}, p))
      .filter(([k, v]) => !HIDE.has(k) && LABELS[k] && v !== null && v !== '' && !k.startsWith('_'))
      .sort((a, b) => rank(a[0]) - rank(b[0]))
      .map(([k, v]) => {
        let val = v;
        if (k === 'usage') val = fmt(v * 100, 0) + ' %';
        else if (k === 'cost') val = fmt(v / 1e6, 2);
        else if (k === 'reconstructed') val = v ? 'да' : 'нет';
        else if (k === 'laying_method') val = v === 'special' ? 'спецпроход' : 'обычная';
        else if (k === 'restriction_type') val = restrictionTitles[v] || v;
        else if (typeof v === 'number') val = fmt(v, Number.isInteger(v) ? 0 : 2);
        return `<tr><td>${esc(LABELS[k] || k)}</td><td>${esc(val)}</td></tr>`;
      }).join('');
    let action = '';
    const oks = lid === 'in-cp' ? (p.oks_id || f.id) : lid.startsWith('res-unconnected') ? p.oks_id : null;
    if (oks && popupActions.oks) action = `<div style="margin-top:6px"><button type="button" class="quiet sm" data-popup-oks="${esc(oks)}">Открыть ОКС →</button></div>`;
    parts.push(`<h4>${esc(title)}</h4><table>${rows}</table>${action}`);
    if (parts.length >= 1) break;
  }
  const others = Math.max(0, seen.size - 1);
  return parts.length ? `<div class="popup">${parts[0]}${others ? `<div class="more">И ещё ${others} — приблизьте карту</div>` : ''}</div>` : null;
}

let clickHandler = null;
/** While drawing, clicks go to the drawing instead of the popups. */
export function captureClicks(fn) {
  clickHandler = fn;
}

map.on('click', (e) => {
  if (clickHandler) {
    clickHandler(e);
    return;
  }
  const layers = map.getStyle().layers.map((l) => l.id).filter((id) => (id.startsWith('in-') || id.startsWith('res-'))
    && map.getLayoutProperty(id, 'visibility') !== 'none');
  const box = [[e.point.x - 7, e.point.y - 7], [e.point.x + 7, e.point.y + 7]];
  const html = popupHtml(map.queryRenderedFeatures(box, {layers}));
  if (!html) return;
  const popup = new maplibregl.Popup({maxWidth: '340px'}).setLngLat(e.lngLat).setHTML(html).addTo(map);
  popup.getElement().addEventListener('click', (ev) => {
    const b = ev.target.closest('[data-popup-oks]');
    if (b && popupActions.oks) {
      popup.remove();
      popupActions.oks(b.dataset.popupOks);
    }
  });
});
map.on('mousemove', (e) => {
  if (clickHandler) {
    map.getCanvas().style.cursor = 'crosshair';
    return;
  }
  const fs = map.queryRenderedFeatures([[e.point.x - 3, e.point.y - 3], [e.point.x + 3, e.point.y + 3]]);
  map.getCanvas().style.cursor = fs.some((f) => f.layer.id.startsWith('res-') || f.layer.id.startsWith('in-')) ? 'pointer' : '';
});

export function closePopups() {
  document.querySelectorAll('.maplibregl-popup').forEach((p) => p.remove());
}

// ---------------------------------------------------------------- hint over the map

export function hint(text, {action, onAction, keep} = {}) {
  const el = document.getElementById('map-hint');
  el.textContent = text || '';
  if (action) {
    const b = document.createElement('button');
    b.type = 'button';
    b.textContent = action;
    b.onclick = onAction;
    el.appendChild(b);
  }
  el.hidden = !text;
  clearTimeout(hint.timer);
  if (text && !keep) hint.timer = setTimeout(() => { el.hidden = true; }, 4500);
}

// ---------------------------------------------------------------- basemap

function setBasemapNow(on) {
  map.setLayoutProperty('osm', 'visibility', on ? 'visible' : 'none');
  prefs.set('osm', on);
}
ready.then(() => { if (prefs.get('osm', false)) setBasemap(true); });

export const applyVisibility = whenReady(applyVisibilityNow);
export const removeLayers = whenReady(removeLayersNow);
export const removeSources = whenReady(removeSourcesNow);
export const setData = whenReady(setDataNow);
export const addLayer = whenReady(addLayerNow);
export const setResultOpacity = whenReady(setResultOpacityNow);
export const showFlowAfter = whenReady(showFlowAfterNow);
export const highlight = whenReady(highlightNow);
export const clearHighlight = whenReady(clearHighlightNow);
export const setBasemap = whenReady(setBasemapNow);
export const fit = whenReady(fitNow);