// The map: input layers of a dataset (vector tiles), layers of a variant (GeoJSON), highlight, the picked object
// (shown in the inspector panel instead of a popup), overlays of other modules (comparison, replay, search trace,
// drawing). Other modules never touch the layers of each other: each owns a prefix and asks this module.

import maplibregl, {type Map as MlMap} from 'maplibre-gl';
import {reactive, ref, shallowRef} from 'vue';
import {fmt} from '@/lib/format';
import {prefs} from '@/lib/prefs';
import {BASE, RESTRICTION_COLORS, type BasePalette} from './palette';

type Any = any;
const EMPTY = {type: 'FeatureCollection', features: []};

export let map: MlMap = null as unknown as MlMap;
let styleReady = false;
let resolveReady: () => void;
export const ready = new Promise<void>((res) => { resolveReady = res; });
const whenReady = <A extends unknown[]>(fn: (...args: A) => void) => (...args: A) => {
  if (styleReady) fn(...args);
  else ready.then(() => fn(...args));
};

/** Space taken by the panels over the map: fitting keeps the data in the visible part. */
export const insets = reactive({top: 80, left: 0, right: 0, bottom: 0});

let palette: BasePalette = BASE.light;
export const theme = ref<'light' | 'dark'>('light');

// ---------------------------------------------------------------- creation

export function createMap(container: HTMLElement) {
  map = new maplibregl.Map({
    container,
    style: {
      version: 8,
      sources: {
        osm: {type: 'raster', tiles: ['https://tile.openstreetmap.org/{z}/{x}/{y}.png'], tileSize: 256, maxzoom: 19,
          attribution: '© участники OpenStreetMap'},
      },
      layers: [
        {id: 'bg', type: 'background', paint: {'background-color': palette.bg}},
        {id: 'osm', type: 'raster', source: 'osm', paint: {'raster-opacity': palette.osmOpacity, 'raster-saturation': -0.6}, layout: {visibility: 'none'}},
      ],
    },
    center: [37.62, 55.75],
    zoom: 10,
    attributionControl: {compact: true},
  });
  map.addControl(new maplibregl.ScaleControl({unit: 'metric'}), 'bottom-right');
  const done = () => {
    if (styleReady) return;
    styleReady = true;
    resolveReady();
  };
  if (map.isStyleLoaded()) done();
  map.once('style.load', done);
  map.once('load', done);
  map.on('click', onClick);
  map.on('mousemove', onMove);
  ready.then(() => { if (prefs.get('osm', false)) setBasemap(true); applyTheme(); });
  return map;
}

// ---------------------------------------------------------------- layer groups (the layers panel)

export interface LayerGroup {
  section?: string;
  title: string;
  hint?: string;
  layers: string[];
  sw?: string;
  stroke?: string;
  kind?: 'line' | 'dot' | 'fill';
  on?: boolean;
  ramp?: boolean;
  restrictions?: boolean;
  flowToggle?: boolean;
  display?: boolean;
}

export const groups = reactive<Record<string, LayerGroup>>({});
export const visible = reactive<Record<string, boolean>>(prefs.get('layers', {}));
export const basemap = ref(prefs.get('osm', false));
export const flowAfter = ref(true);

export function defineGroup(id: string, spec: LayerGroup) {
  groups[id] = Object.assign({on: true}, spec);
  if (!(id in visible)) visible[id] = groups[id].on !== false;
  applyVisibility();
}

export function dropGroups(prefix: string) {
  for (const id of Object.keys(groups)) if (id.startsWith(prefix)) delete groups[id];
}

export function setVisible(id: string, on: boolean) {
  visible[id] = on;
  prefs.set('layers', {...visible});
  applyVisibility();
}

function applyVisibilityNow() {
  for (const [id, g] of Object.entries(groups)) {
    for (const l of g.layers) {
      if (map.getLayer(l)) map.setLayoutProperty(l, 'visibility', visible[id] ? 'visible' : 'none');
    }
  }
}

// ---------------------------------------------------------------- helpers

function removeLayersNow(prefix: string) {
  if (!map.getStyle()) return;
  for (const l of map.getStyle().layers.slice()) if (l.id.startsWith(prefix)) map.removeLayer(l.id);
}

function removeSourcesNow(prefix: string) {
  const st = map.getStyle();
  for (const id of Object.keys(st.sources)) {
    if (id.startsWith(prefix) && !st.layers.some((l: Any) => l.source === id)) map.removeSource(id);
  }
}

function setDataNow(id: string, data: Any, opts: Any = {}) {
  const src = map.getSource(id) as Any;
  if (src) src.setData(data);
  else map.addSource(id, Object.assign({type: 'geojson', data}, opts));
}

function addLayerNow(spec: Any, before?: string) {
  if (map.getLayer(spec.id)) map.removeLayer(spec.id);
  map.addLayer(spec, before && map.getLayer(before) ? before : undefined);
}

const firstOf = (prefixes: string[]) => {
  const l = map.getStyle().layers.find((x) => prefixes.some((p) => x.id.startsWith(p)));
  return l ? l.id : undefined;
};

export function bboxOf(features: Any[]): number[] | null {
  const b = [Infinity, Infinity, -Infinity, -Infinity];
  const walk = (c: Any) => {
    if (typeof c[0] === 'number') {
      b[0] = Math.min(b[0], c[0]); b[1] = Math.min(b[1], c[1]); b[2] = Math.max(b[2], c[0]); b[3] = Math.max(b[3], c[1]);
    } else c.forEach(walk);
  };
  for (const f of features) if (f && f.geometry) walk(f.geometry.coordinates);
  return b[0] === Infinity ? null : b;
}

export function padding(base = 48) {
  const w = map ? map.getContainer().clientWidth : 1200;
  // on a narrow screen the panel is a sheet at the bottom
  if (w < 768) return {top: base + 40, left: 24, right: 24, bottom: base + insets.bottom};
  return {top: insets.top + base / 2, left: insets.left + base, right: insets.right + base, bottom: insets.bottom + base};
}

function fitNow(b: number[] | null, {maxZoom = 17, duration = 600} = {}) {
  if (!b) return;
  map.fitBounds([[b[0], b[1]], [b[2], b[3]]], {padding: padding(), maxZoom, duration});
}

// ---------------------------------------------------------------- input layers

let datasetShown: string | null = null;
let datasetBbox: number[] | null = null;
const restrictionColor = (): Any => ['match', ['get', 'restriction_type'], ...Object.entries(RESTRICTION_COLORS).flat(), '#d6d3d1'];

/** Shows the input layers of a dataset; {@code stamp} (time the data was built) renews cached tiles after a rebuild. */
export async function showDataset(id: string | null, {fitTo = true, stamp = ''} = {}) {
  await ready;
  const key = id ? id + '|' + stamp : null;
  if (datasetShown === key) return;
  const sameDataset = datasetShown && id && datasetShown.startsWith(id + '|');
  datasetShown = key;
  removeLayersNow('in-');
  if (map.getSource('ds')) map.removeSource('ds');
  dropGroups('in-');
  if (!id) {
    datasetBbox = null;
    return;
  }
  if (sameDataset) fitTo = false;
  map.addSource('ds', {type: 'vector', minzoom: 9, maxzoom: 16,
    tiles: [location.origin + '/api/datasets/' + id + '/tiles/{z}/{x}/{y}.mvt' + (stamp ? '?t=' + encodeURIComponent(stamp) : '')]});
  const before = firstOf(['res-', 'cmp-', 'tr-', 'pl-', 'hl-', 'draw-', 'pending-']);
  const add = (l: Any) => map.addLayer(Object.assign({source: 'ds'}, l), before);
  const notEdit = ['!=', ['get', 'source'], 'edit'];
  const p = palette;
  add({id: 'in-restr-fill', type: 'fill', 'source-layer': 'restrictions', filter: ['all', ['==', ['geometry-type'], 'Polygon'], notEdit],
    paint: {'fill-color': restrictionColor(), 'fill-opacity': p.restrictionOpacity}});
  add({id: 'in-restr-line', type: 'line', 'source-layer': 'restrictions', filter: ['==', ['geometry-type'], 'LineString'],
    paint: {'line-color': restrictionColor(), 'line-width': 2, 'line-dasharray': [3, 2]}});
  add({id: 'in-restr-point', type: 'circle', 'source-layer': 'restrictions', filter: ['==', ['geometry-type'], 'Point'],
    paint: {'circle-color': restrictionColor(), 'circle-radius': 3}});
  add({id: 'in-edits-fill', type: 'fill', 'source-layer': 'restrictions', filter: ['all', ['==', ['geometry-type'], 'Polygon'], ['==', ['get', 'source'], 'edit']],
    paint: {'fill-color': '#e11d48', 'fill-opacity': 0.22}});
  add({id: 'in-edits-line', type: 'line', 'source-layer': 'restrictions', filter: ['==', ['get', 'source'], 'edit'],
    paint: {'line-color': '#be123c', 'line-width': 2.5, 'line-dasharray': [2, 1]}});
  add({id: 'in-buildings', type: 'fill', 'source-layer': 'buildings', paint: {'fill-color': p.building, 'fill-outline-color': p.buildingLine}});
  add({id: 'in-oks', type: 'fill', 'source-layer': 'oks', paint: {'fill-color': '#fb923c', 'fill-opacity': 0.35, 'fill-outline-color': '#c2410c'}});
  add({id: 'in-oks-line', type: 'line', 'source-layer': 'oks', paint: {'line-color': '#ea580c', 'line-width': 1.5}});
  add({id: 'in-network', type: 'line', 'source-layer': 'network', layout: {'line-cap': 'round'},
    paint: {'line-color': p.network, 'line-width': ['interpolate', ['linear'], ['coalesce', ['get', 'diameter'], 100], 50, 1.5, 300, 3, 1000, 6], 'line-opacity': 0.85}});
  add({id: 'in-chambers', type: 'circle', 'source-layer': 'chambers', minzoom: 14,
    paint: {'circle-color': p.chamberFill, 'circle-stroke-color': p.network, 'circle-stroke-width': 1.5, 'circle-radius': 3.5}});
  add({id: 'in-cp', type: 'circle', 'source-layer': 'connection_points',
    paint: {'circle-color': '#ea580c', 'circle-radius': 4, 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5}});
  add({id: 'in-source', type: 'circle', 'source-layer': 'source',
    paint: {'circle-color': p.source, 'circle-radius': 8, 'circle-stroke-color': '#fbbf24', 'circle-stroke-width': 3}});
  defineGroup('in-network', {section: 'before', title: 'Существующая сеть', hint: 'толщина — ДУ', layers: ['in-network'], sw: p.network, kind: 'line'});
  defineGroup('in-chambers', {section: 'before', title: 'Существующие камеры', hint: 'при приближении', layers: ['in-chambers'], sw: p.chamberFill, stroke: p.network, kind: 'dot'});
  defineGroup('in-source', {section: 'before', title: 'Источник', layers: ['in-source'], sw: p.source, stroke: '#fbbf24', kind: 'dot'});
  defineGroup('in-oks', {section: 'before', title: 'Перспективные ОКС', hint: 'контур и точка подключения', layers: ['in-oks', 'in-oks-line', 'in-cp'], sw: '#ea580c', kind: 'dot'});
  defineGroup('in-buildings', {section: 'before', title: 'Здания', layers: ['in-buildings'], sw: p.building, kind: 'fill'});
  defineGroup('in-restr', {section: 'before', title: 'Ограничения', layers: ['in-restr-fill', 'in-restr-line', 'in-restr-point'], sw: '#b8bcc2', kind: 'fill', restrictions: true});
  defineGroup('in-edits', {section: 'before', title: 'Ограничения из правок', hint: 'добавлены вручную в этой версии', layers: ['in-edits-fill', 'in-edits-line'], sw: '#fecdd3', stroke: '#be123c', kind: 'fill'});
  applyVisibilityNow();
  try {
    const b = await (await fetch('/api/datasets/' + id + '/bbox')).json();
    if (datasetShown !== key) return;
    datasetBbox = b;
    if (fitTo) map.fitBounds([[b[0], b[1]], [b[2], b[3]]], {padding: padding(32), duration: 0});
  } catch (e) { /* no extent yet */ }
}

/** Back to the whole dataset (or the variant when one is shown). */
export function fitAll() {
  if (variantData) {
    const b = bboxOf(variantData.features.filter((f: Any) => f.properties.object_type !== 'variant_summary'));
    if (b) return fit(b);
  }
  if (datasetBbox) fit(datasetBbox);
}

// ---------------------------------------------------------------- variant layers

let variantShown: string | null = null;
let variantData: Any = null;
let variantKeys = new Set<string>();
let resultOpacity = 1;
const segKey = (f: Any) => f.geometry.coordinates.map((c: number[]) => c[0].toFixed(6) + ',' + c[1].toFixed(6)).join(';');
const dnColor = (): Any => ['interpolate', ['linear'], ['coalesce', ['to-number', ['get', 'diameter']], 50], ...palette.dn];
export const dnWidth = (base: number): Any => ['interpolate', ['linear'], ['zoom'], 12, base * 0.5, 16, base * 1.6, 19, base * 3];

/**
 * Shows a variant. With a transition, pieces common with the previous variant stay, the others fade out and in —
 * the difference is visible by eye.
 */
export async function showVariant(key: string, data: Any, {transitionMs = 0} = {}) {
  await ready;
  const prev = variantData;
  const newKeys = new Set<string>();
  for (const f of data.features) {
    if (f.properties.object_type === 'heat_network' && f.geometry) {
      const k = segKey(f);
      newKeys.add(k);
      f.properties._state = variantKeys.has(k) || !variantShown ? 'same' : 'new';
    }
  }
  const old = prev && prev.features && transitionMs > 0 && variantShown !== key
    ? prev.features.filter((f: Any) => f.properties.object_type === 'heat_network' && f.geometry && !newKeys.has(segKey(f))) : [];
  variantShown = key;
  variantData = data;
  variantKeys = newKeys;
  setDataNow('res', data);
  if (!map.getLayer('res-seg')) addResultLayers();
  const fade = transitionMs > 0 && (old.length || data.features.some((f: Any) => f.properties._state === 'new'));
  setDataNow('res-old', {type: 'FeatureCollection', features: old});
  if (!map.getLayer('res-old')) {
    addLayerNow({id: 'res-old', type: 'line', source: 'res-old', layout: {'line-cap': 'round', 'line-join': 'round'},
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
  applyVisibilityNow();
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

export const hasVariant = () => !!variantShown;

function addResultLayers() {
  const t = (type: string): Any => ['==', ['get', 'object_type'], type];
  const before = firstOf(['cmp-', 'tr-', 'pl-', 'hl-', 'draw-', 'pending-']);
  const add = (l: Any) => addLayerNow(Object.assign({source: 'res'}, l), before);
  const p = palette;
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
    paint: {'line-color': ['step', ['get', 'usage'], '#16a34a', 0.8, '#eab308', 1.0001, '#dc2626'], 'line-width': dnWidth(9), 'line-opacity': 0.45}});
  const same = ['all', t('heat_network'), ['!=', ['get', '_state'], 'new']];
  const fresh = ['all', t('heat_network'), ['==', ['get', '_state'], 'new']];
  add({id: 'res-seg-casing', type: 'line', filter: same, layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': p.casing, 'line-width': dnWidth(4.6)}});
  add({id: 'res-seg', type: 'line', filter: same, layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': dnColor(), 'line-width': dnWidth(3.2)}});
  add({id: 'res-seg-new-casing', type: 'line', filter: fresh, layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': p.casing, 'line-width': dnWidth(4.6)}});
  add({id: 'res-seg-new', type: 'line', filter: fresh, layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': dnColor(), 'line-width': dnWidth(3.2)}});
  add({id: 'res-special', type: 'line', filter: ['all', t('heat_network'), ['==', ['get', 'laying_method'], 'special']],
    paint: {'line-color': '#ffffff', 'line-width': dnWidth(1.2), 'line-dasharray': [1.5, 1.5]}});
  add({id: 'res-flow-anim', type: 'line', filter: t('heat_network'), layout: {'line-cap': 'butt', visibility: 'none'},
    paint: {'line-color': '#ffffff', 'line-opacity': 0.85, 'line-width': ['interpolate', ['linear'], ['get', 'flow_tph'], 0, 1, 50, 2.5, 500, 5], 'line-dasharray': [0, 4, 3]}});
  add({id: 'res-tech', type: 'circle', filter: t('technical_node'), paint: {'circle-color': p.casing, 'circle-radius': 2.5}});
  add({id: 'res-chamber', type: 'circle', filter: t('heat_chamber'), paint: {'circle-color': '#2563eb', 'circle-radius': 5, 'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5}});
  add({id: 'res-tie', type: 'circle', filter: t('attachment'), paint: {'circle-color': '#dc2626', 'circle-radius': 6, 'circle-stroke-color': '#fff', 'circle-stroke-width': 2}});
  defineGroup('res-seg', {section: 'after', title: 'Новые участки', hint: 'цвет — ДУ, мм · пунктир — спецпроход', layers: ['res-seg-casing', 'res-seg', 'res-seg-new-casing', 'res-seg-new', 'res-special', 'res-old'], sw: '#f97316', kind: 'line', ramp: true});
  defineGroup('res-tie', {section: 'after', title: 'Врезки', hint: 'новая камера на участке или врезка в камеру', layers: ['res-tie'], sw: '#dc2626', stroke: '#fff', kind: 'dot'});
  defineGroup('res-chamber', {section: 'after', title: 'Новые камеры', layers: ['res-chamber'], sw: '#2563eb', kind: 'dot'});
  defineGroup('res-tech', {section: 'after', title: 'Технические узлы', hint: 'смена способа прокладки', layers: ['res-tech'], sw: p.casing, kind: 'dot', on: false});
  defineGroup('res-runs', {section: 'after', title: 'Предельная длина', hint: 'зелёный < 80 %, жёлтый до 100 %, красный — превышен', layers: ['res-runs'], sw: '#16a34a', kind: 'line', on: true});
  defineGroup('res-flow', {section: 'change', title: 'Расход существующей сети', hint: 'толщина — расход, цвет — добавленный расход', layers: ['res-flow', 'res-flow-before'], sw: '#0891b2', kind: 'line', flowToggle: true});
  defineGroup('res-short', {section: 'change', title: 'Не хватит пропускной способности', hint: 'понадобится больший ДУ; в стоимость не входит', layers: ['res-short'], sw: '#d946ef', kind: 'line'});
  defineGroup('res-unconnected', {section: 'problems', title: 'ОКС не подключены', layers: ['res-unconnected', 'res-unconnected-area'], sw: '#fecaca', stroke: '#7f1d1d', kind: 'dot'});
  setResultOpacityNow(resultOpacity);
  showFlowAfterNow(flowAfter.value, 0);
}

/** Dims the variant while something is replayed over it. */
function setResultOpacityNow(o: number) {
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
function showFlowAfterNow(after: boolean, ms = 400) {
  flowAfter.value = after;
  if (!map.getLayer('res-flow')) return;
  for (const [l, v] of [['res-flow', after ? 0.9 : 0], ['res-flow-before', after ? 0 : 0.9]] as const) {
    map.setPaintProperty(l, 'line-opacity-transition', {duration: ms});
    map.setPaintProperty(l, 'line-opacity', v * resultOpacity);
  }
}

// ---------------------------------------------------------------- highlight

function highlightNow(geojson: Any, {fitTo = true, maxZoom = 17} = {}) {
  setDataNow('hl', geojson);
  if (!map.getLayer('hl-line')) {
    // a halo under the objects of the variant: highlighted things keep their own colours
    const c = palette.halo;
    // a building or an area: a thin dashed outline, no halo (wide translucent strokes overlap at the corners)
    addLayerNow({id: 'hl-outline', type: 'line', source: 'hl', filter: ['==', ['geometry-type'], 'Polygon'],
      paint: {'line-color': c, 'line-width': 2, 'line-dasharray': [2, 1.5]}});
    addLayerNow({id: 'hl-line', type: 'line', source: 'hl', filter: ['!=', ['geometry-type'], 'Polygon'], layout: {'line-cap': 'round', 'line-join': 'round'},
      paint: {'line-color': c, 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 10, 16, 22, 19, 36], 'line-opacity': 0.4, 'line-blur': 2}},
    map.getLayer('res-short') ? 'res-short' : undefined);
    addLayerNow({id: 'hl-point', type: 'circle', source: 'hl', filter: ['==', ['geometry-type'], 'Point'],
      paint: {'circle-color': 'rgba(0,0,0,0)', 'circle-radius': 16, 'circle-stroke-color': c, 'circle-stroke-width': 3, 'circle-stroke-opacity': 0.8}});
  }
  if (fitTo) fitNow(bboxOf(geojson.features), {maxZoom});
}

function clearHighlightNow() {
  const s = map.getSource('hl') as Any;
  if (s) s.setData(EMPTY);
}

// ---------------------------------------------------------------- the picked object (inspector)

const LABELS: Record<string, string> = {
  diameter: 'ДУ, мм', flow_tph: 'Расход, т/ч', length: 'Длина, м', laying_method: 'Прокладка', cost: 'Стоимость, млн ₽',
  existing_object_id: 'Существующий участок', existing_diameter: 'Существующий ДУ', required_diameter: 'Требуемый ДУ',
  existing_flow_tph: 'Расход до, т/ч', added_flow_tph: 'Добавлено, т/ч', calculated_flow_tph: 'Расход после, т/ч',
  reconstructed: 'Не хватит пропускной способности', existing_chamber: 'Врезка в существующую камеру', measured_length: 'Учитываемая длина, м',
  max_length: 'Предел, м', usage: 'Использовано предела', oks_id: 'ОКС', reason_text: 'Причина', detail: 'Подробности',
  heat_load: 'Нагрузка, Гкал/ч', restriction_type: 'Тип ограничения', id: 'Идентификатор', comment: 'Комментарий',
  start_node_id: 'Начальный узел', end_node_id: 'Конечный узел', name: 'Название', k: 'Коэффициент',
};
const TITLES: Record<string, string> = {
  heat_network: 'Новый участок', attachment: 'Врезка', heat_chamber: 'Новая камера', technical_node: 'Технический узел',
  flow_change: 'Существующий участок: расход', length_run: 'Часть сети одного ДУ', unconnected_oks: 'ОКС не подключён',
};
/** What a click picks first when several objects overlap: points of the result, then its lines, then the input. */
const CLICK_PRIORITY = ['res-tie', 'res-chamber', 'res-tech', 'res-unconnected', 'res-unconnected-area', 'res-seg-new', 'res-seg', 'res-runs',
  'res-short', 'res-flow', 'in-cp', 'in-oks', 'in-source', 'in-chambers', 'in-network', 'in-edits-fill', 'in-restr-point', 'in-restr-line',
  'in-restr-fill', 'in-buildings'];
const LAYER_TITLES: Record<string, string> = {
  'in-network': 'Существующий участок', 'in-chambers': 'Существующая камера', 'in-source': 'Источник', 'in-oks': 'Перспективный ОКС',
  'in-cp': 'Точка подключения', 'in-buildings': 'Существующее здание', 'in-restr-fill': 'Ограничение', 'in-restr-line': 'Ограничение',
  'in-restr-point': 'Ограничение', 'in-edits-fill': 'Ограничение, добавленное вручную', 'in-edits-line': 'Ограничение, добавленное вручную',
};
const HIDE = new Set(['derived', 'reason', 'depth_start', 'depth_end', 'object_type', 'variant_id', '_state', 'source', 'segments', 'path_length', 'total_length']);
const ORDER = ['id', 'oks_id', 'reason_text', 'detail', 'restriction_type', 'comment', 'diameter', 'existing_diameter', 'required_diameter', 'flow_tph',
  'existing_flow_tph', 'added_flow_tph', 'calculated_flow_tph', 'length', 'measured_length', 'max_length', 'usage', 'laying_method', 'cost', 'reconstructed', 'existing_object_id'];

export interface Picked {
  title: string;
  kind: 'input' | 'result';
  color: string;
  rows: {label: string; value: string}[];
  oks: string | null;
  others: number;
  feature: Any;
}

export const picked = shallowRef<Picked | null>(null);
let restrictionTitles: Record<string, string> = {};
export const restrictionTitleMap = reactive<Record<string, string>>({});
export function setRestrictionTitles(t: Record<string, string>) {
  restrictionTitles = t;
  Object.assign(restrictionTitleMap, t);
}

function describe(features: Any[]): Picked | null {
  const layerOf = (f: Any) => f.layer.id.replace(/-casing$/, '').replace(/^res-(special|old)$/, 'res-seg');
  const rank = (f: Any) => { const i = CLICK_PRIORITY.indexOf(layerOf(f)); return i < 0 ? CLICK_PRIORITY.length : i; };
  const sorted = features.slice().sort((a, b) => rank(a) - rank(b));
  const seen = new Set<string>();
  let first: Picked | null = null;
  for (const f of sorted) {
    const p = f.properties || {};
    const lid = layerOf(f);
    if (['in-oks-line', 'in-edits-line', 'res-flow-anim', 'res-flow-before'].includes(lid)) continue;
    const key = lid.replace(/-new$/, '') + '|' + (p.id || f.id || JSON.stringify(p));
    if (seen.has(key)) continue;
    seen.add(key);
    if (first) continue;
    const title = lid.startsWith('in-') ? LAYER_TITLES[lid] || 'Объект' : TITLES[p.object_type] || 'Объект';
    const rk = (k: string) => { const i = ORDER.indexOf(k); return i < 0 ? ORDER.length : i; };
    const rows = Object.entries(Object.assign(f.id != null && !p.id ? {id: f.id} : {}, p) as Record<string, Any>)
      .filter(([k, v]) => !HIDE.has(k) && LABELS[k] && v !== null && v !== '' && !k.startsWith('_'))
      .sort((a, b) => rk(a[0]) - rk(b[0]))
      .map(([k, v]) => {
        let val: string = String(v);
        if (k === 'usage') val = fmt(Number(v) * 100, 0) + ' %';
        else if (k === 'cost') val = fmt(Number(v) / 1e6, 2);
        else if (k === 'reconstructed') val = v === true || v === 'true' ? 'да' : 'нет';
        else if (k === 'laying_method') val = v === 'special' ? 'спецпроход' : 'обычная';
        else if (k === 'restriction_type') val = restrictionTitles[v] || v;
        else if (typeof v === 'number') val = fmt(v, Number.isInteger(v) ? 0 : 2);
        return {label: LABELS[k], value: val};
      });
    const oks = lid === 'in-cp' ? (p.oks_id || f.id) : lid.startsWith('res-unconnected') ? p.oks_id : null;
    const color = lid.startsWith('in-restr') ? (RESTRICTION_COLORS[p.restriction_type] || '#b8bcc2')
      : lid === 'in-cp' || lid === 'in-oks' ? '#ea580c' : lid === 'res-tie' ? '#dc2626' : lid === 'res-chamber' ? '#2563eb'
        : lid.startsWith('res-unconnected') ? '#dc2626' : lid.startsWith('res-seg') ? '#f97316' : lid === 'in-network' ? palette.network : '#94a3b8';
    first = {title, kind: lid.startsWith('in-') ? 'input' : 'result', color, rows, oks: oks ? String(oks) : null, others: 0,
      feature: {type: 'Feature', properties: {}, geometry: f.geometry}};
  }
  if (first) first.others = Math.max(0, seen.size - 1);
  return first;
}

let clickHandler: ((e: Any) => void) | null = null;
/** While drawing, clicks go to the drawing instead of the inspector. */
export function captureClicks(fn: ((e: Any) => void) | null) {
  clickHandler = fn;
}

function onClick(e: Any) {
  if (clickHandler) return clickHandler(e);
  const layers = map.getStyle().layers.map((l) => l.id).filter((id) => (id.startsWith('in-') || id.startsWith('res-'))
    && map.getLayoutProperty(id, 'visibility') !== 'none');
  const box: [[number, number], [number, number]] = [[e.point.x - 7, e.point.y - 7], [e.point.x + 7, e.point.y + 7]];
  const p = describe(map.queryRenderedFeatures(box, {layers}));
  picked.value = p;
  if (p) highlightNow({type: 'FeatureCollection', features: [p.feature]}, {fitTo: false});
  else clearHighlightNow();
}

function onMove(e: Any) {
  if (clickHandler) {
    map.getCanvas().style.cursor = 'crosshair';
    return;
  }
  const fs = map.queryRenderedFeatures([[e.point.x - 3, e.point.y - 3], [e.point.x + 3, e.point.y + 3]]);
  map.getCanvas().style.cursor = fs.some((f) => f.layer.id.startsWith('res-') || f.layer.id.startsWith('in-')) ? 'pointer' : '';
}

export function clearPicked() {
  if (picked.value) {
    picked.value = null;
    clearHighlight();
  }
}

// ---------------------------------------------------------------- hint over the map

export const hintText = ref('');
let hintTimer: ReturnType<typeof setTimeout> | undefined;
export function hint(text: string, {keep = false} = {}) {
  hintText.value = text;
  clearTimeout(hintTimer);
  if (text && !keep) hintTimer = setTimeout(() => { hintText.value = ''; }, 4500);
}

// ---------------------------------------------------------------- basemap and theme

function setBasemapNow(on: boolean) {
  basemap.value = on;
  map.setLayoutProperty('osm', 'visibility', on ? 'visible' : 'none');
  prefs.set('osm', on);
}

/** The base of the map follows the theme of the panels; the colours of the data stay. */
function applyThemeNow() {
  palette = BASE[theme.value];
  const p = palette;
  const set = (l: string, k: string, v: Any) => { if (map.getLayer(l)) map.setPaintProperty(l, k, v); };
  set('bg', 'background-color', p.bg);
  set('osm', 'raster-opacity', p.osmOpacity);
  set('in-buildings', 'fill-color', p.building);
  set('in-buildings', 'fill-outline-color', p.buildingLine);
  set('in-network', 'line-color', p.network);
  set('in-chambers', 'circle-color', p.chamberFill);
  set('in-chambers', 'circle-stroke-color', p.network);
  set('in-source', 'circle-color', p.source);
  set('in-restr-fill', 'fill-opacity', p.restrictionOpacity);
  for (const l of ['res-seg-casing', 'res-seg-new-casing']) set(l, 'line-color', p.casing);
  for (const l of ['res-seg', 'res-seg-new']) set(l, 'line-color', dnColor());
  set('res-tech', 'circle-color', p.casing);
  set('hl-line', 'line-color', p.halo);
  set('hl-outline', 'line-color', p.halo);
  set('hl-point', 'circle-stroke-color', p.halo);
  if (groups['in-network']) groups['in-network'].sw = p.network;
  if (groups['in-buildings']) groups['in-buildings'].sw = p.building;
  if (groups['in-chambers']) Object.assign(groups['in-chambers'], {sw: p.chamberFill, stroke: p.network});
  if (groups['in-source']) groups['in-source'].sw = p.source;
}

export function setTheme(t: 'light' | 'dark') {
  theme.value = t;
  palette = BASE[t];
  if (map) applyTheme();
}

export const dnStops = () => palette.dn;

/** The value of a CSS token (for map layers whose colours must match the interface, e.g. the comparison). */
export const cssVar = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

export function zoomBy(d: number) {
  map.easeTo({zoom: map.getZoom() + d, duration: 250});
}

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
export const applyTheme = whenReady(applyThemeNow);
export const fit = whenReady(fitNow);
