// Replay over the map: the construction of a variant step by step (from its journal) or the route search of one OKS
// (from its trace). Play, pause, step, seek, speed; while a calculation runs the construction is followed live.
// Only the small layers of the replay are redrawn per frame; the panel reads the reactive state below.

import maplibregl from 'maplibre-gl';
import {reactive} from 'vue';
import {fmt} from '@/lib/format';
import {TARGET_TITLES} from '@/lib/terms';
import {addLayer, bboxOf, defineGroup, dropGroups, fit, map, removeLayers, removeSources, setData, setResultOpacity} from './engine';
import {anim} from './anim';
import {pauseFlow} from './flow';

type Any = any;
const STEP_MS = 1500;
const EMPTY = {type: 'FeatureCollection', features: []};

export interface Caption {
  step: string;
  lead: string;
  text: string;
  tone: 'neutral' | 'ok' | 'bad';
  summary: string;
}

/** What the panel shows. */
export const player = reactive({
  open: false,
  mode: 'build' as 'build' | 'search',
  title: '',
  subtitle: '',
  live: false,
  finished: false,
  playing: false,
  k: 0,
  total: 0,
  filterOks: null as string | null,
  oksIds: [] as string[],
  caption: null as Caption | null,
});

let s: Any = null;
let raf = 0;
let markers: maplibregl.Marker[] = [];

export const isOpen = () => !!s;

// ---------------------------------------------------------------- opening

export function openBuild(events: Any[], {title = '', subtitle = '', live = false, filterOks = null as string | null, onClose = undefined as undefined | (() => void),
  autoplay = true, fitTo = true} = {}) {
  close(false);
  s = {mode: 'build', title, subtitle, live, onClose, events: [], steps: [], k: 0, p: 1, playing: false, filterOks, oks: {}, finished: false};
  appendEvents(events);
  setupBuildLayers();
  s.k = live ? Math.max(0, s.steps.length - 1) : 0;
  s.p = 1;
  Object.assign(player, {open: true, mode: 'build', title, subtitle, live, finished: false, filterOks, oksIds: Object.keys(s.oks), caption: null});
  pauseFlow(true);
  if (fitTo) fitToEvents();
  draw();
  if (autoplay && anim.build) play();
}

/** New events of a running calculation. */
export function appendLive(events: Any[], finished: boolean) {
  if (!s || s.mode !== 'build' || !s.live) return;
  const wasAtEnd = s.k >= s.steps.length - 1 && s.p >= 1;
  appendEvents(events);
  s.finished = !!finished;
  player.finished = s.finished;
  player.oksIds = Object.keys(s.oks);
  if (wasAtEnd && anim.live && s.steps.length - 1 > s.k) play();
  else if (!s.playing) draw();
  sync();
}

export function openSearch(trace: Any, {title = '', subtitle = '', onClose = undefined as undefined | (() => void)} = {}) {
  close(false);
  const n = trace.expansions.length;
  const frames = Math.max(1, Math.min(90, n));
  s = {mode: 'search', title, subtitle, onClose, trace, k: 0, p: 1, playing: false, frames, batch: Math.ceil(n / frames)};
  setupSearchLayers(trace);
  Object.assign(player, {open: true, mode: 'search', title, subtitle, live: false, finished: false, filterOks: null, oksIds: [], caption: null});
  pauseFlow(true);
  const b = bboxOf(trace.zones.features.concat(trace.route ? [{geometry: {coordinates: trace.route.coords}}] : []));
  if (b) fit(b, {maxZoom: 18});
  draw();
  if (anim.build) play();
}

export function close(notify = true) {
  if (!s) return;
  const cb = s.onClose;
  stop();
  clearMarkers();
  removeLayers('pl-');
  removeSources('pl-');
  dropGroups('pl-');
  setResultOpacity(1);
  s = null;
  Object.assign(player, {open: false, playing: false, caption: null});
  pauseFlow(false);
  if (notify && cb) cb();
}

export function setFilter(oks: string | null) {
  if (!s || s.mode !== 'build') return;
  openBuild(s.events, {title: s.title, subtitle: s.subtitle, live: s.live, onClose: s.onClose, filterOks: oks, autoplay: true, fitTo: false});
}

// ---------------------------------------------------------------- journal

function appendEvents(events: Any[]) {
  if (s.cache) s.cache.clear();
  s.drawnNet = null;
  s.drawnOks = null;
  for (const e of events) {
    const i = s.events.length;
    s.events.push(e);
    if (e.type === 'start') {
      for (const o of e.oks || []) s.oks[o.id] = o;
      if (!s.steps.length) s.steps.push(i);
    } else if (e.type === 'connect' || e.type === 'unconnected' || e.type === 'rebuild' || e.type === 'finish') {
      if (!s.filterOks || e.oks === s.filterOks || e.type === 'finish') s.steps.push(i);
    }
  }
}

/** Network after the event with index {@code last} (inclusive); memoized — the journal only grows. */
function stateAt(last: number) {
  if (!s.cache) s.cache = new Map();
  if (s.cache.has(last)) return s.cache.get(last);
  const edges = new Map();
  let nodes: Any[] = [];
  let recon: Any[] = [];
  const status: Record<string, string> = {};
  let connected = 0;
  let length = 0;
  for (let i = 0; i <= last && i < s.events.length; i++) {
    const e = s.events[i];
    if (e.type === 'connect' || e.type === 'rebuild') {
      for (const id of e.edges_removed || []) edges.delete(id);
      for (const x of e.edges || []) edges.set(x.id, x);
      nodes = e.nodes || nodes;
      recon = e.recon || recon;
      connected = e.connected;
      length = e.network_length;
      status[e.oks] = e.route ? 'connected' : 'unconnected';
    } else if (e.type === 'unconnected') {
      status[e.oks] = 'unconnected';
    }
  }
  const st = {edges, nodes, recon, status, connected, length};
  s.cache.set(last, st);
  return st;
}

// ---------------------------------------------------------------- layers

function setupBuildLayers() {
  setResultOpacity(0.12);
  for (const id of ['pl-recon', 'pl-net', 'pl-active', 'pl-nodes', 'pl-cands', 'pl-oks']) setData(id, EMPTY);
  addLayer({id: 'pl-recon', type: 'line', source: 'pl-recon', layout: {'line-cap': 'round'},
    paint: {'line-color': ['case', ['>', ['get', 'required_dn'], ['get', 'existing_dn']], '#d946ef', '#22d3ee'],
      'line-width': ['case', ['>', ['get', 'required_dn'], ['get', 'existing_dn']], 9, 5], 'line-opacity': 0.65}});
  addLayer({id: 'pl-net-casing', type: 'line', source: 'pl-net', layout: {'line-cap': 'round', 'line-join': 'round'},
    paint: {'line-color': '#1d2327', 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 2.5, 16, 7, 19, 13]}});
  addLayer({id: 'pl-net', type: 'line', source: 'pl-net', layout: {'line-cap': 'round', 'line-join': 'round'},
    paint: {'line-color': ['interpolate', ['linear'], ['get', 'dn'], 50, '#fde68a', 100, '#fbbf24', 200, '#f97316', 400, '#dc2626', 700, '#9f1239'],
      'line-width': ['interpolate', ['linear'], ['zoom'], 12, 1.6, 16, 5, 19, 9]}});
  addLayer({id: 'pl-active', type: 'line', source: 'pl-active', layout: {'line-cap': 'round', 'line-join': 'round'},
    paint: {'line-color': '#16a34a', 'line-width': ['interpolate', ['linear'], ['zoom'], 12, 3, 16, 7, 19, 12], 'line-opacity': 0.95}});
  addLayer({id: 'pl-nodes', type: 'circle', source: 'pl-nodes',
    paint: {'circle-radius': ['case', ['==', ['get', 'kind'], 'tie'], 6, 4.5], 'circle-color': ['case', ['==', ['get', 'kind'], 'tie'], '#dc2626', '#2563eb'],
      'circle-stroke-color': '#fff', 'circle-stroke-width': 1.5}});
  addLayer({id: 'pl-cands', type: 'circle', source: 'pl-cands',
    paint: {'circle-radius': ['case', ['get', 'chosen'], 9, 6], 'circle-color': 'rgba(0,0,0,0)', 'circle-stroke-width': ['case', ['get', 'chosen'], 3, 2],
      'circle-stroke-color': ['case', ['get', 'chosen'], '#16a34a', '#64748b'], 'circle-stroke-opacity': ['get', 'o']}});
  addLayer({id: 'pl-oks', type: 'circle', source: 'pl-oks',
    paint: {'circle-radius': ['case', ['get', 'current'], 9, 5],
      'circle-color': ['match', ['get', 'status'], 'connected', '#16a34a', 'unconnected', '#dc2626', '#ffffff'],
      'circle-stroke-color': ['match', ['get', 'status'], 'waiting', '#ea580c', '#ffffff'], 'circle-stroke-width': 2}});
  defineGroup('pl-build', {section: 'overlay', title: 'Построение', hint: 'зелёная — прокладываемая трасса · кружки — рассмотренные места врезки',
    layers: ['pl-net', 'pl-net-casing', 'pl-active', 'pl-nodes', 'pl-cands', 'pl-oks', 'pl-recon'], sw: '#16a34a', kind: 'line', on: true});
}

function setupSearchLayers(t: Any) {
  setResultOpacity(0.12);
  setData('pl-zones', t.zones);
  setData('pl-corr', t.corridors);
  const verts = t.vertices.map((c: Any, i: number) => ({type: 'Feature', properties: {i, order: 1e9, start: i < t.starts}, geometry: {type: 'Point', coordinates: c}}));
  const tree: Any[] = [];
  t.expansions.forEach(([v, parent]: number[], order: number) => {
    if (v >= 0) verts[v].properties.order = order;
    if (v >= 0 && parent >= 0) tree.push({type: 'Feature', properties: {order}, geometry: {type: 'LineString', coordinates: [t.vertices[parent], t.vertices[v]]}});
  });
  const nRej = t.rejected.length;
  const nExp = Math.max(1, t.expansions.length);
  const rej = t.rejected.map((e: Any, i: number) => ({type: 'Feature', properties: {order: Math.floor(i * nExp / Math.max(1, nRej)), kind: t.rejected_kinds[i]}, geometry: {type: 'LineString', coordinates: e}}));
  setData('pl-verts', {type: 'FeatureCollection', features: verts});
  setData('pl-tree', {type: 'FeatureCollection', features: tree});
  setData('pl-rej', {type: 'FeatureCollection', features: rej});
  setData('pl-route', EMPTY);
  setData('pl-cp', {type: 'FeatureCollection', features: [{type: 'Feature', properties: {}, geometry: {type: 'Point', coordinates: t.connection_point}}]});
  addLayer({id: 'pl-zones', type: 'fill', source: 'pl-zones', paint: {'fill-color': ['match', ['get', 'kind'], 'own_oks', '#fb923c', '#ef4444'], 'fill-opacity': 0.16, 'fill-outline-color': '#b91c1c'}});
  addLayer({id: 'pl-corr', type: 'fill', source: 'pl-corr', paint: {'fill-color': '#eab308', 'fill-opacity': 0.14}});
  addLayer({id: 'pl-rej', type: 'line', source: 'pl-rej', filter: ['<=', ['get', 'order'], -1], paint: {'line-color': '#dc2626', 'line-width': 0.8, 'line-opacity': 0.35}});
  addLayer({id: 'pl-tree', type: 'line', source: 'pl-tree', filter: ['<=', ['get', 'order'], -1], paint: {'line-color': '#2563eb', 'line-width': 1.2, 'line-opacity': 0.6}});
  addLayer({id: 'pl-verts', type: 'circle', source: 'pl-verts',
    paint: {'circle-radius': ['case', ['get', 'start'], 4, 2.2], 'circle-color': ['case', ['get', 'start'], '#ea580c', '#94a3b8']}});
  addLayer({id: 'pl-verts-on', type: 'circle', source: 'pl-verts', filter: ['<=', ['get', 'order'], -1], paint: {'circle-radius': 3.2, 'circle-color': '#2563eb'}});
  addLayer({id: 'pl-route', type: 'line', source: 'pl-route', layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': '#16a34a', 'line-width': 5}});
  addLayer({id: 'pl-cp', type: 'circle', source: 'pl-cp', paint: {'circle-radius': 8, 'circle-color': '#ea580c', 'circle-stroke-color': '#fff', 'circle-stroke-width': 2}});
  defineGroup('pl-search', {section: 'overlay', title: 'Поиск трассы', hint: 'красное — зоны отступов · жёлтое — коридоры пересечений · синее — просмотренные точки',
    layers: ['pl-zones', 'pl-corr', 'pl-rej', 'pl-tree', 'pl-verts', 'pl-verts-on', 'pl-route', 'pl-cp'], sw: '#2563eb', kind: 'dot', on: true});
}

// ---------------------------------------------------------------- drawing

function fitToEvents() {
  const feats: Any[] = [];
  for (const o of Object.values(s.oks) as Any[]) feats.push({geometry: {coordinates: o.point}});
  for (const e of s.events) if (e.route) feats.push({geometry: {coordinates: e.route.coords}});
  const b = bboxOf(feats);
  if (b) fit(b, {maxZoom: 17, duration: 400});
}

const line = (coords: Any, props?: Any) => ({type: 'Feature', properties: props || {}, geometry: {type: 'LineString', coordinates: coords}});
const point = (c: Any, props?: Any) => ({type: 'Feature', properties: props || {}, geometry: {type: 'Point', coordinates: c}});

function partial(coords: number[][], f: number) {
  if (f >= 1) return coords;
  let total = 0;
  const seg: number[] = [];
  for (let i = 0; i + 1 < coords.length; i++) {
    const d = Math.hypot(coords[i + 1][0] - coords[i][0], (coords[i + 1][1] - coords[i][1]) * 1.7);
    seg.push(d);
    total += d;
  }
  let left = total * Math.max(0, f);
  const out = [coords[0]];
  for (let i = 0; i < seg.length; i++) {
    if (left >= seg[i]) {
      out.push(coords[i + 1]);
      left -= seg[i];
    } else {
      const r = seg[i] > 0 ? left / seg[i] : 0;
      out.push([coords[i][0] + (coords[i + 1][0] - coords[i][0]) * r, coords[i][1] + (coords[i + 1][1] - coords[i][1]) * r]);
      break;
    }
  }
  return out.length >= 2 ? out : [coords[0], coords[0]];
}

function clearMarkers() {
  markers.forEach((m) => m.remove());
  markers = [];
}

function draw() {
  if (!s) return;
  if (s.mode === 'search') return drawSearch();
  const idx = s.steps[s.k];
  const e = s.events[idx];
  const p = s.p;
  const animated = e && (e.type === 'connect' || e.type === 'rebuild') && p < 1;
  const before = stateAt(animated ? idx - 1 : idx);
  const st = animated && p >= 0.85 ? stateAt(idx) : before;
  if (s.drawnNet !== st) {
    s.drawnNet = st;
    setData('pl-net', {type: 'FeatureCollection', features: [...st.edges.values()].map((x: Any) => line(x.coords, {dn: x.dn, flow: x.flow_tph}))});
    setData('pl-recon', {type: 'FeatureCollection', features: st.recon.map((r: Any) => line(r.coords, r))});
    setData('pl-nodes', {type: 'FeatureCollection', features: st.nodes.map((n: Any) => point(n.point, {kind: n.kind}))});
  }
  const status = animated ? stateAt(idx - 1).status : st.status;
  const oksKey = idx + '|' + (animated ? 'a' : 'b');
  if (s.drawnOks !== oksKey) {
    s.drawnOks = oksKey;
    setData('pl-oks', {type: 'FeatureCollection', features: (Object.values(s.oks) as Any[]).map((o) => point(o.point, {
      status: status[o.id] || 'waiting', current: !!e && e.oks === o.id,
    }))});
  }
  if (e && e.route && (animated || s.k === s.steps.length - 1 || !s.playing)) {
    const coords = e.route.coords.slice().reverse();
    const f = animated ? Math.min(1, Math.max(0, (p - 0.3) / 0.55)) : 1;
    setData('pl-active', {type: 'FeatureCollection', features: f > 0 ? [line(partial(coords, f))] : []});
  } else {
    setData('pl-active', EMPTY);
  }
  const alts = e && e.alternatives ? e.alternatives : [];
  const showAlts = e && (animated ? p < 0.9 : !s.playing);
  const o = animated ? Math.min(1, p / 0.2) * (p > 0.75 ? Math.max(0, (0.9 - p) / 0.15) : 1) : 1;
  setData('pl-cands', {type: 'FeatureCollection', features: showAlts ? alts.map((a: Any) => point(a.point, {chosen: !!a.chosen, o: a.chosen ? 1 : o})) : []});
  syncMarkers(showAlts ? alts : [], o);
  caption(e, st);
  sync();
}

function syncMarkers(alts: Any[], opacity: number) {
  const key = alts.map((a) => a.point.join(',')).join('|');
  if (s.markerKey !== key) {
    clearMarkers();
    s.markerKey = key;
    const best = alts.length ? Math.min(...alts.map((a) => a.score)) : 0;
    const placed: {x: number; y: number}[] = [];
    for (const a of alts) {
      const px = map.project(a.point);
      const near = placed.filter((q) => Math.abs(q.x - px.x) < 110 && Math.abs(q.y - px.y) < 22).length;
      placed.push(px);
      const el = document.createElement('div');
      el.className = 'map-label' + (a.chosen ? ' chosen' : '');
      el.textContent = (a.chosen ? '✓ ' : '') + (a.estimate ? 'S ≥ ' : 'S ') + fmt(a.score, 2) + (a.chosen || a.score === best ? '' : ` (+${fmt(a.score - best, 2)})`);
      markers.push(new maplibregl.Marker({element: el, anchor: 'bottom', offset: [0, -10 - 20 * near]}).setLngLat(a.point).addTo(map));
    }
  }
  markers.forEach((m, i) => { m.getElement().style.opacity = String(alts[i] && alts[i].chosen ? 1 : opacity); });
}

function drawSearch() {
  const t = s.trace;
  const last = s.k >= s.frames;
  const upto = last ? 1e9 : s.k * s.batch;
  map.setFilter('pl-tree', ['<=', ['get', 'order'], upto]);
  map.setFilter('pl-verts-on', ['<=', ['get', 'order'], upto]);
  map.setFilter('pl-rej', ['<=', ['get', 'order'], upto]);
  setData('pl-route', last && t.route ? {type: 'FeatureCollection', features: [line(t.route.coords)]} : EMPTY);
  const done = Math.min(t.expansions.length, upto);
  const rejected = t.rejected.length ? Math.min(t.rejected.length, Math.round(t.rejected.length * Math.min(1, done / Math.max(1, t.expansions.length)))) : 0;
  const kinds = (t.rejected_by_kind || []).slice(0, 3).map((x: Any) => `${x.title} — ${fmt(x.count)}`).join(', ');
  let lead = '';
  let tone: Caption['tone'] = 'neutral';
  let text = `Просмотрено точек поворота: ${fmt(done)} из ${fmt(t.vertices.length)}. Отвергнуто отрезков: ${fmt(rejected)}${t.rejected.length >= 4000 ? '+' : ''}${kinds ? ` (чаще всего: ${kinds})` : ''}.`;
  if (last) {
    if (t.route) {
      lead = `Трасса найдена: ${fmt(t.route.length, 0)} м до объекта ${t.route.target_object_id}.`;
      tone = 'ok';
    } else {
      lead = `Трасса не найдена: ${t.failure_text || ''} ${t.failure_detail || ''}`;
      tone = 'bad';
    }
  }
  player.caption = {step: `Кадр ${s.k} из ${s.frames}`, lead, text, tone, summary: `Радиус поиска ${fmt(t.radius_m)} м`};
  sync();
}

function caption(e: Any, st: Any) {
  if (!e) return;
  const n = s.steps.length - 1;
  const summary = `Подключено ${fmt(st.connected)} из ${fmt(Object.keys(s.oks).length)} ОКС · новая сеть ${fmt(st.length, 0)} м · не хватит ДУ: ${fmt(st.recon.filter((r: Any) => r.required_dn > r.existing_dn).length)}`;
  let lead = '';
  let text = '';
  let tone: Caption['tone'] = 'neutral';
  if (e.type === 'start') {
    lead = 'Начало';
    text = `${e.explanation}. ОКС подключаются по одному; для каждого выбирается место врезки с наименьшим вкладом в оценку S.`;
  } else if (e.type === 'finish') {
    lead = 'Итог';
    tone = 'ok';
    text = `S = ${fmt(e.score, 2)} · ${fmt(e.calculated_cost / 1e6, 1)} млн ₽${e.unconnected ? ` · не подключено: ${e.unconnected}` : ''}.`;
  } else if (e.type === 'unconnected') {
    lead = `ОКС ${e.oks} остался без маршрута`;
    tone = 'bad';
    text = e.reason_text + (e.detail ? ` (${e.detail})` : '') + '.';
  } else {
    const r = e.route;
    if (!r && e.straightened) {
      lead = `Участок к ОКС ${e.oks} проложен заново`;
      text = 'Между теми же камерами, с меньшим числом поворотов.';
    } else if (!r) {
      lead = `Перестройка без ОКС ${e.oks}`;
      text = 'Трасса для него не найдена — сеть пересобрана без него.';
    } else {
      const alts = (e.alternatives || []).length;
      lead = (e.type === 'rebuild' ? `ОКС ${e.oks} подключён заново` : `ОКС ${e.oks} подключён`) + ` · ${fmt(e.flow_tph, 1)} т/ч`;
      text = `${TARGET_TITLES[r.target.kind] || ''}${r.target.object_id ? ' ' + r.target.object_id : ''}; трасса ${fmt(r.length, 0)} м, ДУ ${r.dn}, поворотов ${r.turns}. ` +
        (alts > 1 ? `Рассмотрено мест врезки: ${alts} — выбрано лучшее по S.` : 'Других допустимых мест врезки не найдено.');
    }
  }
  player.caption = {step: `Шаг ${s.k} из ${n}`, lead, text, tone, summary};
}

// ---------------------------------------------------------------- playback

const total = () => (s.mode === 'search' ? s.frames : s.steps.length - 1);

function sync() {
  if (!s) return;
  player.k = s.k;
  player.total = total();
  player.playing = s.playing;
}

export function play() {
  if (!s) return;
  if (s.k >= total() && s.p >= 1) {
    s.k = 0;
    s.p = 1;
  }
  s.playing = true;
  let last = performance.now();
  cancelAnimationFrame(raf);
  const tick = (now: number) => {
    if (!s || !s.playing) return;
    const dt = Math.min(100, now - last);
    last = now;
    const stepMs = (s.mode === 'search' ? 90 : STEP_MS) / anim.speed;
    if (s.p >= 1) {
      if (s.k >= total()) {
        if (!(s.live && !s.finished)) s.playing = false;
        draw();
        if (s.playing) raf = requestAnimationFrame(tick);
        return;
      }
      s.k++;
      s.p = 0;
    }
    s.p = Math.min(1, s.p + dt / stepMs);
    draw();
    raf = requestAnimationFrame(tick);
  };
  raf = requestAnimationFrame(tick);
  sync();
}

export function stop() {
  if (!s) return;
  s.playing = false;
  cancelAnimationFrame(raf);
  sync();
}

export function toggle() {
  if (!s) return;
  if (s.playing) stop(); else play();
}

export function seek(k: number) {
  if (!s) return;
  stop();
  s.k = Math.max(0, Math.min(total(), k));
  s.p = 1;
  draw();
}

export function stepForward() {
  if (!s) return;
  stop();
  if (s.k >= total()) return;
  s.k++;
  s.p = 0;
  let last = performance.now();
  const stepMs = (s.mode === 'search' ? 90 : STEP_MS) / anim.speed;
  const tick = (now: number) => {
    if (!s || s.playing) return;
    s.p = Math.min(1, s.p + (now - last) / stepMs);
    last = now;
    draw();
    if (s.p < 1) raf = requestAnimationFrame(tick);
  };
  raf = requestAnimationFrame(tick);
}

export const stepBack = () => s && seek(s.k - 1);

/** Read-only state for end-to-end checks: mode, step, total, playing. */
export function snapshot() {
  return s ? {mode: s.mode, step: s.k, total: total(), playing: s.playing, live: !!s.live, filter: s.filterOks || null} : null;
}
