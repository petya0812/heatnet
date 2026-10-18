<script setup lang="ts">
// Step 1 — data: the library of datasets (projects first), upload by drag and drop with progress and cancel.
// After an upload the check of the dataset opens by itself.

import {computed, onMounted, ref, watch} from 'vue';
import {useRouter} from 'vue-router';
import {ArrowRight, CircleAlert, Ellipsis, FileUp, FolderOpen, Search, Trash2, Upload, X, Layers, GitBranch} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {api} from '@/api/client';
import {ago, bytes, count, fmt, GROUPS, isActive, splitName, STATUS_RU} from '@/lib/format';
import {toast} from '@/lib/toast';
import {confirmDialog} from '@/lib/dialogs';
import {prefs} from '@/lib/prefs';
import PanelLayout from '@/components/shell/PanelLayout.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiProgress from '@/components/ui/UiProgress.vue';
import UiEmpty from '@/components/ui/UiEmpty.vue';
import UiMenu from '@/components/ui/UiMenu.vue';
import UiIconButton from '@/components/ui/UiIconButton.vue';
import UiSegmented from '@/components/ui/UiSegmented.vue';

const app = useApp();
const router = useRouter();
const fileInput = ref<HTMLInputElement | null>(null);
const over = ref(false);
const upload = ref<{name: string; loaded: number; total: number; abort: () => void} | null>(null);
const q = ref('');
const group = ref<string>(prefs.get('dsGroup', 'all'));
watch(group, (v) => prefs.set('dsGroup', v));
const progress = ref<Record<string, {read: number; total: number}>>({});

onMounted(() => app.loadDatasets().catch((e) => toast(e.message, true)));

const roots = computed(() => app.datasets.filter((d) => d.root_id === d.id || !d.parent_id));
const versionsOf = (d: any) => app.datasets.filter((v) => v.root_id === d.id && v.id !== d.id).sort((a, b) => a.version - b.version);
const counts = computed(() => {
  const c: Record<string, number> = {all: roots.value.length};
  for (const d of roots.value) {
    const g = splitName(d.name).group;
    c[g] = (c[g] || 0) + 1;
  }
  return c;
});
const groupOptions = computed(() => [{value: 'all', label: `Все ${counts.value.all || ''}`},
  ...GROUPS.filter((g) => counts.value[g.key]).map((g) => ({value: g.key, label: `${g.title} ${counts.value[g.key]}`}))]);
const shown = computed(() => {
  const needle = q.value.trim().toLowerCase();
  const list = roots.value.filter((d) => (group.value === 'all' || splitName(d.name).group === group.value)
    && (!needle || (d.name || '').toLowerCase().includes(needle)));
  // projects first, then uploads, examples, large sets — the order of the groups
  const order = (d: any) => GROUPS.findIndex((g) => g.key === splitName(d.name).group);
  return list.slice().sort((a, b) => order(a) - order(b) || (a.created_at < b.created_at ? 1 : -1));
});

const oksOf = (d: any) => {
  const tc = d.type_counts || {};
  return tc.oks_connection_point != null ? tc.oks_connection_point : tc.oks_future;
};

function start(file?: File | null) {
  if (!file) return;
  const task = api.upload(file, (loaded, total) => { if (upload.value) Object.assign(upload.value, {loaded, total}); });
  upload.value = {name: file.name, loaded: 0, total: file.size, abort: task.abort};
  task.promise.then((r) => {
    upload.value = null;
    toast(`Файл «${file.name}» передан, идёт импорт`, {tone: 'ok'});
    app.loadDatasets();
    router.push({name: 'check', params: {ds: r.id}});
  }).catch((e) => {
    upload.value = null;
    if (!e.aborted) toast('Загрузка не удалась: ' + e.message, true);
  });
}

function onDrop(e: DragEvent) {
  over.value = false;
  start(e.dataTransfer?.files[0]);
}

async function remove(d: any) {
  if (!await confirmDialog('Удалить набор?', `«${splitName(d.name).title}» будет удалён вместе с версиями и всеми расчётами. Это нельзя отменить.`)) return;
  try {
    await api.deleteDataset(d.id);
    toast('Набор удалён');
    app.loadDatasets();
  } catch (e: any) { toast(e.message, true); }
}

async function cancelImport(d: any) {
  try { await api.cancelImport(d.id); app.loadDatasets(); } catch (e: any) { toast(e.message, true); }
}

// progress of datasets being imported
watch(() => app.datasets.filter((d) => isActive(d.status)).map((d) => d.id + d.status).join(), async () => {
  for (const d of app.datasets.filter((x) => isActive(x.status))) {
    try {
      const x: any = await api.dataset(d.id);
      if (x.progress && x.progress.bytes_total) progress.value[d.id] = {read: x.progress.bytes_read, total: x.progress.bytes_total};
    } catch (e) { /* gone */ }
  }
}, {immediate: true});

const open = (id: string) => router.push({name: 'check', params: {ds: id}});
</script>

<template>
  <PanelLayout title="Наборы данных">
    <template #sub>
      <p class="mt-1 text-[13.5px] text-ink-3">Загрузите GeoJSON или откройте набор — дальше проверка, расчёт и результат по шагам.</p>
    </template>

    <!-- upload -->
    <div class="relative rounded-[18px] border-2 border-dashed p-4 transition-colors"
      :class="over ? 'border-accent bg-accent-soft' : 'border-line-strong bg-surface-2 hover:border-[color-mix(in_srgb,var(--line-strong),var(--accent)_40%)]'"
      @dragover.prevent="over = true" @dragleave="over = false" @drop.prevent="onDrop">
      <input ref="fileInput" type="file" class="sr-only" accept=".geojson,.json,application/geo+json,application/json" aria-label="Файл GeoJSON"
        @change="start(($event.target as HTMLInputElement).files?.[0]); ($event.target as HTMLInputElement).value = ''" />
      <div v-if="upload" class="flex items-center gap-3">
        <div class="flex size-11 shrink-0 items-center justify-center rounded-2xl bg-accent-soft text-accent"><FileUp class="size-5" aria-hidden="true" /></div>
        <div class="min-w-0 flex-1">
          <div class="truncate text-[14px] font-medium">{{ upload.name }}</div>
          <UiProgress class="mt-1.5" :value="upload.loaded" :max="upload.total" :label="`${bytes(upload.loaded)} из ${bytes(upload.total)}`" />
        </div>
        <UiIconButton :icon="X" label="Отменить загрузку" @click="upload.abort()" />
      </div>
      <div v-else class="flex items-center gap-3.5">
        <div class="flex size-11 shrink-0 items-center justify-center rounded-2xl bg-surface-solid text-accent shadow-soft"><Upload class="size-5" aria-hidden="true" /></div>
        <div class="min-w-0 flex-1">
          <div class="text-[14px] font-semibold">Перетащите файл GeoJSON сюда</div>
          <div class="text-[12.5px] text-ink-3">FeatureCollection в EPSG:4326, до 3 ГБ: сеть, камеры, источник, ОКС, здания, ограничения</div>
        </div>
        <UiButton variant="primary" :icon="FolderOpen" @click="fileInput?.click()">Выбрать</UiButton>
      </div>
    </div>

    <!-- filter -->
    <div class="flex flex-col gap-2 pt-1">
      <label class="flex h-10 items-center gap-2 rounded-xl border border-line-strong bg-surface-solid px-3 shadow-soft focus-within:border-accent focus-within:shadow-[0_0_0_3px_var(--accent-soft)]">
        <Search class="size-4 shrink-0 text-ink-3" aria-hidden="true" />
        <input v-model="q" class="min-w-0 flex-1 bg-transparent text-sm outline-none placeholder:text-ink-4" placeholder="Найти по названию" aria-label="Найти набор по названию" />
        <button v-if="q" type="button" class="text-ink-3 hover:text-ink" aria-label="Очистить поиск" @click="q = ''"><X class="size-4" aria-hidden="true" /></button>
      </label>
      <UiSegmented v-if="groupOptions.length > 2" v-model="group" :options="groupOptions" label="Группа наборов" size="sm" />
    </div>

    <!-- list -->
    <div v-if="!app.datasetsLoaded" class="space-y-2">
      <div v-for="i in 4" :key="i" class="skeleton h-[92px]" />
    </div>
    <UiEmpty v-else-if="!roots.length" :icon="Layers" title="Наборов пока нет" text="Загрузите файл — после импорта откроется проверка данных." />
    <UiEmpty v-else-if="!shown.length" :icon="Search" title="Ничего не найдено" text="Измените запрос или группу.">
      <UiButton size="sm" @click="q = ''; group = 'all'">Показать все</UiButton>
    </UiEmpty>
    <ul v-else class="space-y-2" aria-label="Наборы">
      <li v-for="d in shown" :key="d.id" class="enter">
        <article class="card group relative cursor-pointer p-3.5 transition-[box-shadow,border-color] hover:border-line-strong hover:shadow-[0_6px_18px_-8px_rgb(16_24_40/0.22)]"
          @click="open(d.id)">
          <div class="flex items-start gap-3">
            <div class="min-w-0 flex-1">
              <div class="flex items-center gap-2">
                <a :href="`#/datasets/${d.id}`" class="min-w-0 truncate text-[15px] font-semibold text-ink after:absolute after:inset-0 focus-visible:outline-none" @click.prevent>
                  {{ splitName(d.name).title }}
                </a>
                <UiBadge v-if="d.status !== 'READY'" :tone="d.status === 'FAILED' ? 'bad' : d.status === 'CANCELLED' ? 'neutral' : 'accent'" dot>{{ STATUS_RU[d.status] || d.status }}</UiBadge>
              </div>
              <div class="mt-1 flex flex-wrap gap-x-3 gap-y-0.5 text-[12.5px] text-ink-3">
                <span v-if="oksOf(d) != null"><b class="font-semibold text-ink-2">{{ fmt(oksOf(d)) }}</b> ОКС</span>
                <span v-if="d.type_counts?.heat_network != null"><b class="font-semibold text-ink-2">{{ fmt(d.type_counts.heat_network) }}</b> участков сети</span>
                <span v-if="d.type_counts?.restriction != null"><b class="font-semibold text-ink-2">{{ fmt(d.type_counts.restriction) }}</b> ограничений</span>
              </div>
              <div class="mt-1 text-xs text-ink-3">{{ ago(d.created_at) }} · {{ bytes(d.file_bytes) }}<template v-if="d.run_count"> · {{ count(d.run_count, 'расчёт', 'расчёта', 'расчётов') }}</template></div>
            </div>
            <div class="relative z-10 flex items-center gap-1" @click.stop>
              <UiButton v-if="d.run_count && d.status === 'READY'" size="xs" variant="soft" :icon-right="ArrowRight"
                @click="router.push({name: 'latest', params: {ds: d.id}})">Результат</UiButton>
              <UiMenu :items="[
                ...(isActive(d.status) ? [{label: 'Отменить импорт', icon: X, onSelect: () => cancelImport(d)}] : []),
                {label: 'Удалить набор', icon: Trash2, danger: true, onSelect: () => remove(d)},
              ]">
                <UiIconButton :icon="Ellipsis" label="Действия с набором" size="sm" />
              </UiMenu>
            </div>
          </div>
          <div v-if="d.error" class="mt-2 flex items-start gap-2 rounded-xl bg-bad-soft px-3 py-2 text-[12.5px] text-bad"><CircleAlert class="mt-px size-4 shrink-0" aria-hidden="true" />{{ d.error }}</div>
          <UiProgress v-if="isActive(d.status)" class="mt-2.5" :value="progress[d.id]?.read" :max="progress[d.id]?.total"
            :label="progress[d.id] ? `прочитано ${fmt(100 * progress[d.id].read / progress[d.id].total)} %` : 'разбор объектов'" />
          <div v-if="versionsOf(d).length" class="relative z-10 mt-2.5 space-y-1 border-t border-line pt-2" @click.stop>
            <a v-for="v in versionsOf(d)" :key="v.id" :href="`#/datasets/${v.id}`"
              class="flex items-center gap-2 rounded-lg px-1.5 py-1 text-[12.5px] text-ink-2 hover:bg-surface-2 hover:text-ink">
              <GitBranch class="size-3.5 text-ink-4" aria-hidden="true" />
              <span class="font-medium">Версия {{ v.version }}</span>
              <span class="min-w-0 flex-1 truncate text-ink-3">{{ v.note || (v.edits ? count(v.edits.length, 'правка', 'правки', 'правок') : '') }}</span>
              <span v-if="v.run_count" class="text-ink-3">{{ count(v.run_count, 'расчёт', 'расчёта', 'расчётов') }}</span>
            </a>
          </div>
        </article>
      </li>
    </ul>
  </PanelLayout>
</template>
