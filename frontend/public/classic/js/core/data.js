// Loading from the server into the store, with polling while something is in progress (import, calculation).

import {api} from './api.js';
import {ACTIVE} from './format.js';
import {store} from './store.js';

const timers = {};
function later(name, fn, ms) {
  clearTimeout(timers[name]);
  timers[name] = setTimeout(() => fn().catch((e) => console.warn(name, e)), ms);
}
function cancel(name) {
  clearTimeout(timers[name]);
}

export async function loadCatalog() {
  if (!store.catalog) store.set('catalog', await api.params());
  return store.catalog;
}

export async function loadDatasets() {
  const list = await api.datasets();
  store.set('datasets', list);
  if (list.some((d) => ACTIVE.includes(d.status))) later('datasets', loadDatasets, 1500);
  return list;
}

/** Overview of the current dataset (check step); polled while it is being imported or built. */
export async function loadOverview(id) {
  cancel('overview');
  const o = await api.overview(id);
  if (store.route.dataset !== id) return null;
  store.set('overview', o);
  if (ACTIVE.includes(o.status)) {
    try {
      const d = await api.dataset(id);
      if (d.progress) o.progress = d.progress;
    } catch (e) { /* ignore */ }
    later('overview', () => loadOverview(id), 1200);
  }
  return o;
}

export async function loadRuns(id) {
  cancel('runs');
  const list = await api.runs(id);
  if (store.route.dataset !== id) return null;
  store.set('runs', list);
  if (list.some((r) => ACTIVE.includes(r.status))) later('runs', () => loadRuns(id), 1500);
  return list;
}

export async function loadRun(id) {
  cancel('run');
  const r = await api.run(id);
  if (store.route.run !== id && store.watchRun !== id) return null;
  store.set('run', r);
  if (ACTIVE.includes(r.status)) later('run', () => loadRun(id), 1000);
  return r;
}

const variantCache = new Map();
export async function loadVariant(run, v) {
  const key = run + '|' + v;
  let data = variantCache.get(key);
  if (!data) {
    data = await api.variant(run, v);
    variantCache.set(key, data);
    if (variantCache.size > 12) variantCache.delete(variantCache.keys().next().value);
  }
  return data;
}

const journalCache = new Map();
export async function loadJournal(run, v) {
  const key = run + '|' + v;
  if (!journalCache.has(key)) journalCache.set(key, await api.journal(run, v));
  return journalCache.get(key);
}

/** Runs of all versions of the dataset family (for the comparison). */
export async function loadFamilyRuns(datasetId) {
  const list = store.datasets.length ? store.datasets : await loadDatasets();
  const ds = list.find((d) => d.id === datasetId);
  const root = ds ? ds.root_id : datasetId;
  const family = list.filter((d) => d.root_id === root && d.status === 'READY');
  const out = [];
  for (const d of family) {
    for (const r of await api.runs(d.id)) out.push(Object.assign({dataset_version: d.version}, r));
  }
  return out.sort((a, b) => (a.created_at < b.created_at ? 1 : -1));
}
