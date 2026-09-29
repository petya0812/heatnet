// The address is the place in the workflow: reload or a link sent to a colleague opens the same.
//   #/                                                     step 1 — data
//   #/datasets/{ds}                                        step 2 — check
//   #/datasets/{ds}/calculate                              step 3 — calculation
//   #/datasets/{ds}/runs/{run}/variants/{v}[/oks/{oks}]    step 4 — result (an OKS card)
//   #/datasets/{ds}/compare/{runA}:{vA}/{runB}:{vB}        step 4, tab «Сравнение»
//   #/datasets/{ds}/runs/{run}/variants/{v}/export         step 4, tab «Выгрузка»

import {emit, store} from './store.js';

/** The four steps of the stepper; comparison and download are tabs of the result. */
export const STEPS = [
  {id: 'data', n: 1, title: 'Исходные данные', icon: 'database'},
  {id: 'check', n: 2, title: 'Проверка данных', icon: 'shield-check'},
  {id: 'calc', n: 3, title: 'Расчёт', icon: 'calculator'},
  {id: 'result', n: 4, title: 'Результат', icon: 'map'},
];

/** Views that live inside the result step, as its tabs. */
export const RESULT_TABS = [
  {id: 'result', title: 'Вариант', icon: 'map'},
  {id: 'compare', title: 'Сравнение', icon: 'compare'},
  {id: 'export', title: 'Выгрузка', icon: 'download'},
];

export const ALL_VIEWS = ['data', 'check', 'calc', 'result', 'compare', 'export'];

/** The step of the stepper a view belongs to. */
export const stepOf = (view) => (view === 'compare' || view === 'export' ? 'result' : view);

export function parse(hash) {
  const [path, query] = (hash || '').replace(/^#/, '').split('?');
  const p = path.split('/').filter(Boolean).map(decodeURIComponent);
  const q = Object.fromEntries(new URLSearchParams(query || ''));
  const r = {step: 'data', query: q};
  if (p[0] !== 'datasets' || !p[1]) return r;
  r.dataset = p[1];
  r.step = 'check';
  if (p[2] === 'calculate') r.step = 'calc';
  if (p[2] === 'compare') {
    r.step = 'compare';
    if (p[3]) [r.runA, r.variantA] = p[3].split(':');
    if (p[4]) [r.runB, r.variantB] = p[4].split(':');
  }
  if (p[2] === 'runs' && p[3]) {
    r.run = p[3];
    r.step = 'result';
    r.variant = p[4] === 'variants' && p[5] ? p[5] : null;
    if (p[6] === 'oks' && p[7]) r.oks = p[7];
    if (p[6] === 'export') r.step = 'export';
  }
  return r;
}

export function format(r) {
  if (!r.dataset || r.step === 'data') return '#/';
  const ds = `#/datasets/${encodeURIComponent(r.dataset)}`;
  const q = r.query && Object.keys(r.query).length ? '?' + new URLSearchParams(r.query) : '';
  switch (r.step) {
    case 'calc': return `${ds}/calculate${q}`;
    case 'compare':
      return `${ds}/compare${r.runA ? `/${r.runA}:${r.variantA || 1}` : ''}${r.runB ? `/${r.runB}:${r.variantB || 1}` : ''}${q}`;
    case 'result':
    case 'export': {
      if (!r.run) return `${ds}/calculate`;
      const base = `${ds}/runs/${r.run}/variants/${encodeURIComponent(r.variant || '1')}`;
      if (r.step === 'export') return base + '/export';
      return base + (r.oks ? `/oks/${encodeURIComponent(r.oks)}` : '') + q;
    }
    default: return ds + q;
  }
}

/** Go to a place: patch of the current route; {replace} keeps the history entry. */
export function go(patch, {replace = false} = {}) {
  const next = Object.assign({}, store.route, {query: {}}, patch);
  const hash = format(next);
  if (location.hash === hash) {
    handle();
    return;
  }
  if (replace) {
    history.replaceState(null, '', hash);
    handle();
  } else {
    location.hash = hash;
  }
}

function handle() {
  const r = parse(location.hash);
  store.route = r;
  emit('route', r);
}

export function start() {
  window.addEventListener('hashchange', handle);
  handle();
}
