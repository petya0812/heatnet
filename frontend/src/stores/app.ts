// Shared state of the page. What is on the server stays on the server: here are only the loaded copies and the
// current place in the workflow (which lives in the address). Polling runs while something is in progress.

import {defineStore} from 'pinia';
import {computed, ref, shallowRef} from 'vue';
import {useRoute} from 'vue-router';
import {api} from '@/api/client';
import {isActive} from '@/lib/format';

const timers: Record<string, ReturnType<typeof setTimeout>> = {};
function later(name: string, fn: () => Promise<unknown>, ms: number) {
  clearTimeout(timers[name]);
  timers[name] = setTimeout(() => fn().catch((e) => console.warn(name, e)), ms);
}
const cancel = (name: string) => clearTimeout(timers[name]);

/** Same JSON as before: the polling brings identical answers every second, nothing has to redraw then. */
const same = (a: unknown, b: unknown) => {
  if (a === b) return true;
  try { return JSON.stringify(a) === JSON.stringify(b); } catch (e) { return false; }
};

export type Step = 'data' | 'check' | 'calc' | 'result';

export const useApp = defineStore('app', () => {
  const route = useRoute();

  const datasets = ref<any[]>([]);
  const datasetsLoaded = ref(false);
  const overview = ref<any>(null);
  const runs = ref<any[]>([]);
  const runsFor = ref<string | null>(null);
  const run = ref<any>(null);
  const catalog = ref<any>(null);
  const variantData = shallowRef<any>(null);
  const variantKey = ref<string | null>(null);
  const server = ref<{running: number; queued: number; offline: boolean}>({running: 0, queued: 0, offline: false});
  /** A run followed while it is calculated, even when the address shows another place. */
  const watchRun = ref<string | null>(null);

  // ------------------------------------------------------------------ the place in the address

  const datasetId = computed(() => (route.params.ds as string) || null);
  const runId = computed(() => (route.params.run as string) || null);
  const variantId = computed(() => (route.params.v as string) || '1');
  const oksId = computed(() => (route.params.oks as string) || null);
  const view = computed(() => (route.meta.view as string) || 'data');
  const step = computed<Step>(() => (route.meta.step as Step) || 'data');

  const currentOverview = computed(() => (overview.value && overview.value.id === datasetId.value ? overview.value : null));
  const currentRun = computed(() => (run.value && run.value.id === runId.value ? run.value : null));
  const doneRuns = computed(() => (runsFor.value === datasetId.value ? runs.value.filter((r) => r.status === 'DONE') : []));
  const activeRun = computed(() => (runsFor.value === datasetId.value ? runs.value.find((r) => isActive(r.status)) : null));
  const blocking = computed(() => (currentOverview.value?.readiness || []).filter((c: any) => c.blocking));
  const ready = computed(() => currentOverview.value?.status === 'READY' && !blocking.value.length);

  // ------------------------------------------------------------------ loading

  async function loadCatalog() {
    if (!catalog.value) catalog.value = await api.params();
    return catalog.value;
  }

  async function loadDatasets() {
    const list = await api.datasets();
    if (!same(datasets.value, list)) datasets.value = list;
    datasetsLoaded.value = true;
    if (list.some((d) => isActive(d.status))) later('datasets', loadDatasets, 1500);
    return list;
  }

  async function loadOverview(id: string) {
    cancel('overview');
    const o: any = await api.overview(id);
    if (datasetId.value !== id) return null;
    if (isActive(o.status)) {
      try {
        const d: any = await api.dataset(id);
        if (d.progress) o.progress = d.progress;
      } catch (e) { /* ignore */ }
      later('overview', () => loadOverview(id), 1200);
    }
    if (!same(overview.value, o)) overview.value = o;
    return o;
  }

  async function loadRuns(id: string) {
    cancel('runs');
    const list = await api.runs(id);
    if (datasetId.value !== id) return null;
    if (!same(runs.value, list) || runsFor.value !== id) runs.value = list;
    runsFor.value = id;
    if (list.some((r) => isActive(r.status))) later('runs', () => loadRuns(id), 1500);
    return list;
  }

  async function loadRun(id: string) {
    cancel('run');
    const r: any = await api.run(id);
    if (runId.value !== id && watchRun.value !== id) return null;
    if (!same(run.value, r)) run.value = r;
    if (isActive(r.status)) later('run', () => loadRun(id), 1000);
    return r;
  }

  const variantCache = new Map<string, any>();
  async function loadVariant(runKey: string, v: string) {
    const key = runKey + '|' + v;
    let data = variantCache.get(key);
    if (!data) {
      data = await api.variant(runKey, v);
      variantCache.set(key, data);
      if (variantCache.size > 12) variantCache.delete(variantCache.keys().next().value as string);
    }
    return data;
  }

  const journalCache = new Map<string, any>();
  async function loadJournal(runKey: string, v: string) {
    const key = runKey + '|' + v;
    if (!journalCache.has(key)) journalCache.set(key, await api.journal(runKey, v));
    return journalCache.get(key);
  }

  /** Runs of all versions of the dataset family (for the comparison). */
  async function loadFamilyRuns(id: string) {
    const list = datasets.value.length ? datasets.value : await loadDatasets();
    const ds = list.find((d) => d.id === id);
    const root = ds ? ds.root_id : id;
    const family = list.filter((d) => d.root_id === root && d.status === 'READY');
    const out: any[] = [];
    for (const d of family) {
      for (const r of await api.runs(d.id)) out.push(Object.assign({dataset_version: d.version}, r));
    }
    return out.sort((a, b) => (a.created_at < b.created_at ? 1 : -1));
  }

  async function loadStatus() {
    try {
      const s: any = await api.status();
      server.value = {running: s.running, queued: s.queued, offline: false};
    } catch (e) {
      server.value = {running: 0, queued: 0, offline: true};
    }
    later('status', loadStatus, 4000);
  }

  function resetDataset() {
    overview.value = null;
    runs.value = [];
    runsFor.value = null;
    run.value = null;
    variantData.value = null;
    variantKey.value = null;
  }

  return {
    datasets, datasetsLoaded, overview, runs, runsFor, run, catalog, variantData, variantKey, server, watchRun,
    datasetId, runId, variantId, oksId, view, step, currentOverview, currentRun, doneRuns, activeRun, blocking, ready,
    loadCatalog, loadDatasets, loadOverview, loadRuns, loadRun, loadVariant, loadJournal, loadFamilyRuns, loadStatus, resetDataset,
  };
});

export {prefs} from '@/lib/prefs';
