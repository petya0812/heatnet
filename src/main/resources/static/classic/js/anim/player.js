// Replay panel at the bottom of the map: the construction of a variant step by step (from its journal) or the route
// search of one OKS (from its trace). Play, pause, step, seek, speed, "result at once"; while a calculation runs the
// construction is followed live. Only the small layers of the replay are redrawn per frame.

import {h, on, replace} from '../core/dom.js';
import {fmt} from '../core/format.js';
import {addLayer, bboxOf, defineGroup, dropGroups, fit, map, removeLayers, removeSources, setData, setResultOpacity} from '../map/map.js';
import {anim, setAnim} from './settings.js';
import {icon, iconButton} from '../ui/components.js';
import {TARGET_TITLES} from '../ui/glossary.js';

/* global maplibregl */

const root = () => document.getElementById('player');
const STEP_MS = 1500;
const EMPTY = {type: 'FeatureCollection', features: []};

let s = null;           // current session
let raf = 0;
let markers = [];
let keyHandler = null;

export const isOpen = () => !!s;

// ---------------------------------------------------------------- opening

/** Replay of a construction journal. */
export function openBuild(events, {title, subtitle, live = false, filterOks = null, onClose, autoplay = true, fit = true} = {}) {
  close(false);
  s = {mode: 'build', title, subtitle, live, onClose, events: [], steps: [], k: 0, p: 1, playing: false, filterOks, oks: {}, finished: false};
  appendEvents(events);
  setupBuildLayers();
  s.k = live ? Math.max(0, s.steps.length - 1) : 0;
  s.p = 1;
  renderPanel();
  if (fit) fitToEvents();   // при смене фильтра карту не двигаем: человек уже выбрал, куда смотреть
  draw();
  if (autoplay && anim.build) play();
  installKeys();
}

/** New events of a running calculation. */
export function appendLive(events, finished) {
  if (!s || s.mode !== 'build' || !s.live) return;
  const wasAtEnd = s.k >= s.steps.length - 1 && s.p >= 1;
  appendEvents(events);
  s.finished = !!finished;
  const live = root().querySelector('.live');
  if (live) live.textContent = s.finished ? 'Расчёт завершён' : 'Идёт расчёт';
  if (wasAtEnd && anim.live && s.steps.length - 1 > s.k) play();
  else if (!s.playing) draw();
  syncControls();
}

/** Replay of the route search of one OKS. */
export function openSearch(trace, {title, subtitle, onClose} = {}) {
  close(false);
  const n = trace.expansions.length;
  const frames = Math.max(1, Math.min(90, n));
  s = {mode: 'search', title, subtitle, onClose, trace, k: 0, p: 1, playing: false, frames, batch: Math.ceil(n / frames)};
  setupSearchLayers(trace);
  renderPanel();
  const b = bboxOf(trace.zones.features.concat(trace.route ? [{geometry: {coordinates: trace.route.coords}}] : []));
  if (b) fit(b, {maxZoom: 18});
  draw();
  if (anim.build) play();
  installKeys();
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
  root().hidden = true;
  replace(root());
  if (keyHandler) document.removeEventListener('keydown', keyHandler);
  keyHandler = null;
  s = null;
  if (notify && cb) cb();
}

// ---------------------------------------------------------------- journal

function appendEvents(events) {
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
function stateAt(last) {
  if (!s.cache) s.cache = new Map();
  if (s.cache.has(last)) return s.cache.get(last);
  const st = computeState(last);
  s.cache.set(last, st);
  return st;
}

function computeState(last) {
  const edges = new Map();
  let nodes = [];
  let recon = [];
  const status = {};
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
  return {edges, nodes, recon, status, connected, length};
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
      'circle-stroke-color': ['match', ['get', 'status'], 'waiting', '#c2410c', '#ffffff'], 'circle-stroke-width': 2}});
  defineGroup('pl-build', {section: 'overlay', title: 'Построение', hint: 'зелёная — прокладываемая трасса · кружки — рассмотренные места врезки',
    layers: ['pl-net', 'pl-net-casing', 'pl-active', 'pl-nodes', 'pl-cands', 'pl-oks', 'pl-recon'], sw: '#16a34a', kind: 'line', on: true});
}

function setupSearchLayers(t) {
  setResultOpacity(0.12);
  setData('pl-zones', t.zones);
  setData('pl-corr', t.corridors);
  const verts = t.vertices.map((c, i) => ({type: 'Feature', properties: {i, order: 1e9, start: i < t.starts}, geometry: {type: 'Point', coordinates: c}}));
  const tree = [];
  t.expansions.forEach(([v, parent], order) => {
    if (v >= 0) verts[v].properties.order = order;
    if (v >= 0 && parent >= 0) {
      tree.push({type: 'Feature', properties: {order}, geometry: {type: 'LineString', coordinates: [t.vertices[parent], t.vertices[v]]}});
    }
  });
  const nRej = t.rejected.length;
  const nExp = Math.max(1, t.expansions.length);
  const rej = t.rejected.map((e, i) => ({type: 'Feature', properties: {order: Math.floor(i * nExp / Math.max(1, nRej)), kind: t.rejected_kinds[i]}, geometry: {type: 'LineString', coordinates: e}}));
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
    paint: {'circle-radius': ['case', ['get', 'start'], 4, 2.2], 'circle-color': ['case', ['get', 'start'], '#c2410c', '#94a3b8']}});
  addLayer({id: 'pl-verts-on', type: 'circle', source: 'pl-verts', filter: ['<=', ['get', 'order'], -1], paint: {'circle-radius': 3.2, 'circle-color': '#2563eb'}});
  addLayer({id: 'pl-route', type: 'line', source: 'pl-route', layout: {'line-cap': 'round', 'line-join': 'round'}, paint: {'line-color': '#16a34a', 'line-width': 5}});
  addLayer({id: 'pl-cp', type: 'circle', source: 'pl-cp', paint: {'circle-radius': 8, 'circle-color': '#c2410c', 'circle-stroke-color': '#fff', 'circle-stroke-width': 2}});
  defineGroup('pl-search', {section: 'overlay', title: 'Поиск трассы', hint: 'красное — зоны отступов · жёлтое — коридоры пересечений · синее — просмотренные точки · красные линии — отвергнутые отрезки',
    layers: ['pl-zones', 'pl-corr', 'pl-rej', 'pl-tree', 'pl-verts', 'pl-verts-on', 'pl-route', 'pl-cp'], sw: '#2563eb', kind: 'dot', on: true});
}

// ---------------------------------------------------------------- drawing

function fitToEvents() {
  const feats = [];
  for (const o of Object.values(s.oks)) feats.push({geometry: {coordinates: o.point}});
  for (const e of s.events) if (e.route) feats.push({geometry: {coordinates: e.route.coords}});
  const b = bboxOf(feats);
  if (b) fit(b, {maxZoom: 17, duration: 400});
}

const line = (coords, props) => ({type: 'Feature', properties: props || {}, geometry: {type: 'LineString', coordinates: coords}});
const point = (c, props) => ({type: 'Feature', properties: props || {}, geometry: {type: 'Point', coordinates: c}});

function partial(coords, f) {
  if (f >= 1) return coords;
  let total = 0;
  const seg = [];
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
  // the network changes only between steps: redraw it only then
  if (s.drawnNet !== st) {
    s.drawnNet = st;
    setData('pl-net', {type: 'FeatureCollection', features: [...st.edges.values()].map((x) => line(x.coords, {dn: x.dn, flow: x.flow_tph}))});
    setData('pl-recon', {type: 'FeatureCollection', features: st.recon.map((r) => line(r.coords, r))});
    setData('pl-nodes', {type: 'FeatureCollection', features: st.nodes.map((n) => point(n.point, {kind: n.kind}))});
  }
  const status = animated ? stateAt(idx - 1).status : st.status;
  const oksKey = idx + '|' + (animated ? 'a' : 'b');
  if (s.drawnOks !== oksKey) {
    s.drawnOks = oksKey;
    setData('pl-oks', {type: 'FeatureCollection', features: Object.values(s.oks).map((o) => point(o.point, {
      status: status[o.id] || 'waiting', current: !!e && e.oks === o.id,
    }))});
  }
  // the route being laid: from the OKS towards the network
  if (e && e.route && (animated || s.k === s.steps.length - 1 || !s.playing)) {
    const coords = e.route.coords.slice().reverse();
    const f = animated ? Math.min(1, Math.max(0, (p - 0.3) / 0.55)) : 1;
    setData('pl-active', {type: 'FeatureCollection', features: f > 0 ? [line(partial(coords, f))] : []});
  } else {
    setData('pl-active', EMPTY);
  }
  // alternatives of the tie-in: appear, the chosen one stays
  const alts = e && e.alternatives ? e.alternatives : [];
  const showAlts = e && (animated ? p < 0.9 : !s.playing);
  const o = animated ? Math.min(1, p / 0.2) * (p > 0.75 ? Math.max(0, (0.9 - p) / 0.15) : 1) : 1;
  setData('pl-cands', {type: 'FeatureCollection', features: showAlts ? alts.map((a) => point(a.point, {chosen: !!a.chosen, o: a.chosen ? 1 : o})) : []});
  syncMarkers(showAlts ? alts : [], o);
  renderCaption(e, st);
  syncControls();
}

function syncMarkers(alts, opacity) {
  const key = alts.map((a) => a.point.join(',')).join('|');
  if (s.markerKey !== key) {
    clearMarkers();
    s.markerKey = key;
    const best = alts.length ? Math.min(...alts.map((a) => a.score)) : 0;
    const placed = [];
    for (const a of alts) {
      // labels of places close to each other are stacked instead of overlapping
      const px = map.project(a.point);
      const near = placed.filter((q) => Math.abs(q.x - px.x) < 110 && Math.abs(q.y - px.y) < 22).length;
      placed.push(px);
      const el = h('div', {class: 'map-label' + (a.chosen ? ' chosen' : '')},
        (a.chosen ? '✓ ' : '') + (a.estimate ? 'S ≥ ' : 'S ') + fmt(a.score, 2) + (a.chosen || a.score === best ? '' : ` (+${fmt(a.score - best, 2)})`));
      markers.push(new maplibregl.Marker({element: el, anchor: 'bottom', offset: [0, -10 - 20 * near]}).setLngLat(a.point).addTo(map));
    }
  }
  markers.forEach((m, i) => { m.getElement().style.opacity = alts[i] && alts[i].chosen ? 1 : opacity; });
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
  const kinds = (t.rejected_by_kind || []).slice(0, 3).map((x) => `${x.title} — ${fmt(x.count)}`).join(', ');
  const text = [
    h('b', null, `Просмотрено точек поворота: ${fmt(done)} из ${fmt(t.vertices.length)}. `),
    `Отвергнуто отрезков: ${fmt(rejected)}${t.rejected.length >= 4000 ? '+' : ''}${kinds ? ` (чаще всего: ${kinds})` : ''}. `,
    `Радиус поиска ${fmt(t.radius_m)} м.`,
  ];
  if (last) {
    text.push(h('div', null, t.route ? h('b', {style: {color: 'var(--ok)'}}, `Трасса найдена: ${fmt(t.route.length, 0)} м до объекта ${t.route.target_object_id}.`)
      : h('b', {style: {color: 'var(--bad)'}}, `Трасса не найдена: ${t.failure_text || ''} ${t.failure_detail || ''}`)));
  }
  replace(root().querySelector('.caption'), text);
  syncControls();
}

function renderCaption(e, st) {
  const cap = root().querySelector('.caption');
  if (!cap || !e) return;
  const n = s.steps.length - 1;
  const summary = h('div', {class: 'small muted'}, `Подключено ОКС: ${fmt(st.connected)} из ${fmt(Object.keys(s.oks).length)} · новая сеть ${fmt(st.length, 0)} м · участков существующей сети с добавленным расходом: ${fmt(st.recon.length)}, из них не хватит пропускной способности: ${fmt(st.recon.filter((r) => r.required_dn > r.existing_dn).length)}`);
  let main;
  if (e.type === 'start') {
    main = [h('b', null, 'Начало. '), `${e.explanation}. ОКС подключаются по одному; для каждого выбирается место врезки с наименьшим вкладом в оценку S.`];
  } else if (e.type === 'finish') {
    main = [h('b', null, 'Итог. '), `S = ${fmt(e.score, 2)} · ${fmt(e.calculated_cost / 1e6, 1)} млн ₽${e.unconnected ? ` · не подключено: ${e.unconnected}` : ''}. Длина в сводке варианта — по участкам файла результата: общие участки считаются один раз.`];
  } else if (e.type === 'unconnected') {
    main = [h('b', {style: {color: 'var(--bad)'}}, `ОКС ${e.oks} остался без маршрута: `), e.reason_text + (e.detail ? ` (${e.detail})` : '') + '.'];
  } else {
    const r = e.route;
    const verb = e.type === 'rebuild' ? 'Перестройка: ОКС ' + e.oks + ' отключён и подключён заново' : 'ОКС ' + e.oks + ' подключён';
    if (!r) {
      main = [h('b', null, `Перестройка: сеть пересобрана без ОКС ${e.oks}`), ' — трасса для него не найдена.'];
    } else {
      const alts = (e.alternatives || []).length;
      main = [h('b', null, `${verb} (${fmt(e.flow_tph, 1)} т/ч): `),
        `${TARGET_TITLES[r.target.kind] || ''}${r.target.object_id ? ' ' + r.target.object_id : ''}; трасса ${fmt(r.length, 0)} м, ДУ ${r.dn}, поворотов ${r.turns}. `,
        alts > 1 ? `Рассмотрено мест врезки: ${alts} — выбрано лучшее по S.` : 'Других допустимых мест врезки не найдено.'];
    }
  }
  replace(cap, h('div', null, h('span', {class: 'muted'}, `Шаг ${s.k} из ${n}. `), main), summary);
}

// ---------------------------------------------------------------- playback

function total() {
  return s.mode === 'search' ? s.frames : s.steps.length - 1;
}

function play() {
  if (!s) return;
  if (s.k >= total() && s.p >= 1) {
    s.k = 0;
    s.p = 1;
  }
  s.playing = true;
  let last = performance.now();
  cancelAnimationFrame(raf);
  const tick = (now) => {
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
  syncControls();
}

function stop() {
  if (!s) return;
  s.playing = false;
  cancelAnimationFrame(raf);
  syncControls();
}

function seek(k) {
  stop();
  s.k = Math.max(0, Math.min(total(), k));
  s.p = 1;
  draw();
}

function stepForward() {
  if (!s) return;
  stop();
  if (s.k >= total()) return;
  s.k++;
  s.p = 0;
  // one animated step
  let last = performance.now();
  const stepMs = (s.mode === 'search' ? 90 : STEP_MS) / anim.speed;
  const tick = (now) => {
    if (!s || s.playing) return;
    s.p = Math.min(1, s.p + (now - last) / stepMs);
    last = now;
    draw();
    if (s.p < 1) raf = requestAnimationFrame(tick);
  };
  raf = requestAnimationFrame(tick);
}

// ---------------------------------------------------------------- panel

function renderPanel() {
  const el = root();
  el.hidden = false;
  const oksIds = s.mode === 'build' ? Object.keys(s.oks) : [];
  replace(el,
    h('div', {class: 'ph'},
      h('span', {class: 'ttl'}, icon(s.mode === 'search' ? 'route' : 'play'), s.title || (s.mode === 'search' ? 'Поиск трассы' : 'Построение варианта')),
      s.subtitle ? h('span', {class: 'muted'}, s.subtitle) : null,
      s.live ? h('span', {class: 'live'}, s.finished ? 'Расчёт завершён' : 'Идёт расчёт') : null,
      h('span', {class: 'spacer'}),
      s.mode === 'build' && oksIds.length ? h('label', {class: 'small muted'}, icon('filter', 'sm'),
        h('select', {dataset: {act: 'filter'}, 'aria-label': 'Фильтр'},
          h('option', {value: ''}, 'Весь вариант'),
          oksIds.map((id) => h('option', {value: id, selected: s.filterOks === id}, 'ОКС ' + id)))) : null,
      h('label', {class: 'small muted'}, icon('gauge', 'sm'),
        h('select', {dataset: {act: 'speed'}, 'aria-label': 'Скорость'}, [0.5, 1, 2, 4, 8].map((x) => h('option', {value: x, selected: anim.speed === x}, x + '×')))),
      iconButton('x', 'Закрыть', {class: 'sm', dataset: {act: 'close'}})),
    h('div', {class: 'controls'},
      iconButton('skip-back', 'К началу', {class: 'sm', dataset: {act: 'first'}}),
      iconButton('step-back', 'Шаг назад (←)', {class: 'sm', dataset: {act: 'prev'}}),
      iconButton('play', 'Пуск / пауза (пробел)', {class: 'sm primary', dataset: {act: 'play'}}),
      iconButton('step-forward', 'Шаг вперёд (→)', {class: 'sm', dataset: {act: 'next'}}),
      iconButton('skip-forward', 'В конец', {class: 'sm', dataset: {act: 'end'}}),
      h('input', {type: 'range', min: 0, max: total(), value: s.k, dataset: {act: 'seek'}, 'aria-label': 'Шаг'})),
    h('div', {class: 'caption'}));
  if (s.mode === 'build') el.querySelector('.caption').append(h('span', {class: 'muted'}, 'Загрузка…'));
}

function syncControls() {
  const el = root();
  if (!s || el.hidden) return;
  const range = el.querySelector('[data-act=seek]');
  if (range) {
    range.max = total();
    range.value = s.k;
  }
  const play = el.querySelector('[data-act=play]');
  if (play) replace(play, icon(s.playing ? 'pause' : 'play'));
}

let installed = false;
export function installPlayer() {
  if (installed) return;
  installed = true;
  const el = root();
  on(el, 'click', '[data-act]', (e, t) => {
    if (!s) return;
    switch (t.dataset.act) {
      case 'close': close(); break;
      case 'play': if (s.playing) stop(); else play(); break;
      case 'prev': seek(s.k - 1); break;
      case 'next': stepForward(); break;
      case 'first': seek(0); break;
      case 'end': seek(total()); break;
      default:
    }
  });
  on(el, 'input', '[data-act=seek]', (e, t) => seek(Number(t.value)));
  on(el, 'change', '[data-act=speed]', (e, t) => setAnim('speed', Number(t.value)));
  on(el, 'change', '[data-act=filter]', (e, t) => {
    if (!s) return;
    const events = s.events;
    const opts = {title: s.title, subtitle: s.subtitle, live: s.live, onClose: s.onClose, filterOks: t.value || null, autoplay: true, fit: false};
    openBuild(events, opts);
  });
}

function installKeys() {
  if (keyHandler) return;
  keyHandler = (e) => {
    if (!s || e.target.closest('input, textarea, select')) return;
    if (e.key === ' ') {
      e.preventDefault();
      if (s.playing) stop(); else play();
    } else if (e.key === 'ArrowRight') stepForward();
    else if (e.key === 'ArrowLeft') seek(s.k - 1);
  };
  document.addEventListener('keydown', keyHandler);
}

/** Read-only state for end-to-end checks: mode, step, total, playing. */
export function snapshot() {
  return s ? {mode: s.mode, step: s.k, total: total(), playing: s.playing, live: !!s.live, filter: s.filterOks || null} : null;
}
