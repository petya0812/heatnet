<script setup lang="ts">
// Calculations in progress on the server, from any screen: the count in the top bar, the list on click. There is no
// list of runs across datasets in the API, so the list is gathered from the runs of each dataset that has runs
// (GET /api/datasets/{id}/runs) — only while the list is open; afterwards only the datasets with an active run are
// asked again, and all of them once more when the count of /api/status does not match.

import {computed, onBeforeUnmount, ref, watch} from 'vue';
import {useRouter} from 'vue-router';
import {PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger} from 'reka-ui';
import {ArrowRight, Clapperboard, Clock, LoaderCircle, X} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {api} from '@/api/client';
import {fmt, isActive, splitName} from '@/lib/format';
import {live, watchRun} from '@/lib/live';
import {toast} from '@/lib/toast';
import UiIconButton from '@/components/ui/UiIconButton.vue';
import UiProgress from '@/components/ui/UiProgress.vue';

const app = useApp();
const router = useRouter();
const open = ref(false);
const busy = computed(() => app.server.running + app.server.queued);

const list = ref<any[]>([]);
const loaded = ref(false);
const now = ref(Date.now());
let withActive = new Set<string>();
let timer: ReturnType<typeof setTimeout> | undefined;
let round = 0; // a list opened again does not start a second polling

async function refresh(r = round) {
  try {
    let ids = [...withActive];
    const names: Record<string, string> = Object.fromEntries(list.value.map((r) => [r.dataset_id, r.dataset_name]));
    if (!loaded.value || list.value.length !== busy.value) {
      const all = await api.datasets();
      for (const d of all) names[d.id] = splitName(d.name).title + (d.version > 1 ? ` · версия ${d.version}` : '');
      ids = all.filter((d) => d.status === 'READY' && d.run_count > 0).map((d) => d.id);
    }
    const lists = await Promise.all(ids.map((id) => api.runs(id).catch(() => [] as any[])));
    if (r !== round) return;
    const active = lists.flat().filter((r) => isActive(r.status)).map((r) => ({...r, dataset_name: names[r.dataset_id] || 'Набор'}));
    // calculated first, then the queue in the order it will be taken
    active.sort((a, b) => (a.status === b.status ? (a.created_at < b.created_at ? -1 : 1) : a.status === 'RUNNING' ? -1 : 1));
    list.value = active;
    withActive = new Set(active.map((r) => r.dataset_id));
    loaded.value = true;
  } catch (e) { /* the status in the bar shows the lost connection */ }
  if (r !== round) return;
  now.value = Date.now();
  if (open.value) timer = setTimeout(() => refresh(r), 2000);
}

watch(open, (v) => {
  clearTimeout(timer);
  round++;
  if (v) { loaded.value = false; refresh(); }
});
onBeforeUnmount(() => clearTimeout(timer));

/** «2:05» since start, or since it was queued. */
function elapsed(r: any) {
  const t = Date.parse(r.status === 'RUNNING' && r.started_at ? r.started_at : r.created_at);
  if (Number.isNaN(t)) return '';
  const s = Math.max(0, Math.round((now.value - t) / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}
const progressLabel = (r: any) => (r.progress
  ? `${r.progress.stage || 'Считается'}${r.progress.total ? ` · ${fmt(r.progress.done)} из ${fmt(r.progress.total)}` : ''}`
  : 'Считается');

const reloadDataset = (ds: string) => { if (app.datasetId === ds) app.loadRuns(ds); app.loadDatasets(); };
function openRun(r: any) {
  open.value = false;
  router.push({name: 'result', params: {ds: r.dataset_id, run: r.id, v: '1'}});
}
async function showBuild(r: any) {
  open.value = false;
  await router.push({name: 'calc', params: {ds: r.dataset_id}, query: {tab: 'history'}});
  if (live.value) live.value.closed = false;
  watchRun(r.id, r.dataset_id, r.name || 'Расчёт', () => reloadDataset(r.dataset_id));
}
async function cancel(r: any) {
  try {
    await api.cancelRun(r.id);
    list.value = list.value.filter((x) => x.id !== r.id);
    reloadDataset(r.dataset_id);
    app.loadStatus();
  } catch (e: any) {
    toast(e.message, true);
  }
}
</script>

<template>
  <PopoverRoot v-if="busy || open" v-model:open="open">
    <!-- no tooltip around the trigger: the list itself says what is calculated -->
    <PopoverTrigger class="flex h-9 items-center gap-1.5 rounded-full bg-accent-soft px-3 text-[13px] font-medium text-accent-ink transition-colors hover:bg-[color-mix(in_srgb,var(--accent-soft),var(--accent)_12%)]"
      :aria-label="`Расчёты на сервере: считается ${fmt(app.server.running)}, в очереди ${fmt(app.server.queued)}. Показать список`">
      <LoaderCircle v-if="busy" class="size-4 animate-spin" aria-hidden="true" />{{ busy }}
    </PopoverTrigger>
    <PopoverPortal>
      <PopoverContent :side-offset="10" align="end" :collision-padding="10"
        class="z-[180] flex max-h-[min(70vh,560px)] w-[380px] max-w-[calc(100vw-20px)] flex-col rounded-[20px] border border-line bg-surface-solid shadow-pop data-[state=open]:animate-[fade-up_.14s_ease-out]">
        <div class="shrink-0 px-4 pb-2 pt-3.5">
          <div class="text-[15px] font-semibold">Расчёты на сервере</div>
          <div class="text-xs text-ink-3" role="status">
            {{ busy ? `считается ${fmt(app.server.running)} · в очереди ${fmt(app.server.queued)}` : 'Сейчас ничего не считается' }}
          </div>
        </div>
        <div class="scroll-thin min-h-0 flex-1 overflow-y-auto px-2 pb-2">
          <div v-if="!loaded" class="space-y-2 px-2 pb-2"><div class="skeleton h-14" /><div class="skeleton h-14" /></div>
          <ul v-else-if="list.length" class="divide-y divide-line" aria-label="Расчёты в работе">
            <li v-for="r in list" :key="r.id" class="px-2 py-3">
              <div class="flex items-start gap-2.5">
                <span class="mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-lg" :class="r.status === 'RUNNING' ? 'bg-accent-soft text-accent' : 'bg-surface-2 text-ink-3'">
                  <LoaderCircle v-if="r.status === 'RUNNING'" class="size-4 animate-spin" aria-hidden="true" />
                  <Clock v-else class="size-4" aria-hidden="true" />
                </span>
                <div class="min-w-0 flex-1">
                  <div class="truncate text-[13.5px] font-semibold text-ink" :title="r.name || 'Расчёт'">{{ r.name || 'Расчёт' }}</div>
                  <RouterLink :to="{name: 'calc', params: {ds: r.dataset_id}, query: {tab: 'history'}}" class="block truncate text-xs text-ink-3 hover:text-ink hover:underline"
                    :title="'История расчётов набора ' + r.dataset_name" @click="open = false">{{ r.dataset_name }}</RouterLink>
                  <div class="num mt-0.5 text-xs text-ink-2">{{ r.status === 'RUNNING' ? 'Считается' : 'В очереди' }} · {{ elapsed(r) }}</div>
                </div>
                <div class="flex shrink-0 items-center">
                  <UiIconButton v-if="r.status === 'RUNNING'" :icon="Clapperboard" label="Показать построение на карте" size="sm" @click="showBuild(r)" />
                  <UiIconButton :icon="ArrowRight" label="Открыть расчёт" size="sm" @click="openRun(r)" />
                  <UiIconButton :icon="X" label="Отменить расчёт" size="sm" variant="danger" @click="cancel(r)" />
                </div>
              </div>
              <UiProgress v-if="r.status === 'RUNNING'" class="ml-[38px] mt-2" :value="r.progress?.done" :max="r.progress?.total || undefined" :label="progressLabel(r)" />
            </li>
          </ul>
          <p v-else class="px-2 pb-2 text-[13px] text-ink-3">{{ busy ? 'Список обновляется…' : 'Все расчёты завершены.' }}</p>
        </div>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>
