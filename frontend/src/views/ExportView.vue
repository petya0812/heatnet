<script setup lang="ts">
// Result, tab «Выгрузка» — what can be downloaded, what it is for and how large it is. Files are saved only when
// the user presses the button.

import {computed, reactive, watch} from 'vue';
import {useRouter} from 'vue-router';
import {Download, ExternalLink, FileJson, FileText, Map as MapIcon, Route, Trophy} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {bytes, ago} from '@/lib/format';
import PanelLayout from '@/components/shell/PanelLayout.vue';
import ResultTabs from '@/components/shell/ResultTabs.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiEmpty from '@/components/ui/UiEmpty.vue';
import UiSegmented from '@/components/ui/UiSegmented.vue';

const app = useApp();
const router = useRouter();
const run = computed(() => app.currentRun);
const sizes = reactive<Record<string, number | null>>({});

/** The size of a file — by a HEAD request; streamed answers have no size beforehand. */
async function measure(url: string) {
  if (url in sizes) return;
  sizes[url] = null;
  try {
    const r = await fetch(url, {method: 'HEAD'});
    const len = r.headers.get('content-length');
    sizes[url] = len ? Number(len) : -1;
  } catch (e) {
    sizes[url] = -1;
  }
}

const files = computed(() => {
  const r = run.value;
  if (!r || r.status !== 'DONE') return [];
  const v = app.variantId;
  const short = r.id.slice(0, 8);
  return [
    {icon: FileJson, title: 'Результат расчёта', ext: 'GeoJSON', main: true, purpose: 'Все варианты в формате обмена: новые участки, камеры, технические узлы, сводка каждого варианта',
      url: `/api/runs/${r.id}/result.geojson`, size: r.result_bytes, name: `result-${short}.geojson`},
    {icon: MapIcon, title: `Вариант ${v}`, ext: 'GeoJSON', purpose: 'Только выбранный вариант в том же формате — для ГИС',
      url: `/api/runs/${r.id}/variants/${v}.geojson?derived=false&download=true`, size: undefined, name: `variant-${short}-v${v}.geojson`, measure: true},
    {icon: FileText, title: `Отчёт по варианту ${v}`, ext: 'HTML', purpose: 'Сводка, карта, показатели, ОКС, проверка правил, параметры — страница для печати',
      url: `/api/runs/${r.id}/variants/${v}/report.html?download=true`, view: `/api/runs/${r.id}/variants/${v}/report.html`, name: `report-${short}-v${v}.html`},
    {icon: Route, title: 'Журнал построения', ext: 'JSON', purpose: `Порядок подключения ОКС и оценки мест врезки варианта ${v} — для разбора решений`,
      url: `/api/runs/${r.id}/journal?variant=${v}`, name: `journal-${short}-v${v}.json`},
  ];
});
watch(files, (fs) => { for (const f of fs) if (f.measure) measure(f.url); }, {immediate: true});
const sizeOf = (f: any) => (f.size != null ? f.size : sizes[f.url]);
const variants = computed(() => (run.value?.summary || []).slice().sort((a: any, b: any) => a.rank - b.rank));
const pickVariant = (v: string) => router.push({name: 'export', params: {ds: app.datasetId, run: app.runId, v}});
</script>

<template>
  <PanelLayout :title="run ? run.name || 'Расчёт' : 'Выгрузка'" eyebrow="Шаг 4 · Выгрузка"
    :back="{to: {name: 'calc', params: {ds: app.datasetId}, query: {tab: 'history'}}, label: 'История расчётов'}">
    <template #sub><div v-if="run" class="mt-1 text-[13px] text-ink-3">{{ ago(run.created_at) }}</div></template>
    <template #header><ResultTabs /></template>

    <div v-if="!run" class="space-y-3"><div class="skeleton h-24" /><div class="skeleton h-24" /></div>
    <UiEmpty v-else-if="run.status !== 'DONE'" :icon="Download" title="Результата пока нет" text="Выгрузка доступна после завершения расчёта." />
    <template v-else>
      <div v-if="variants.length > 1" class="flex items-center gap-2">
        <span class="text-[13px] text-ink-2">Вариант</span>
        <UiSegmented :model-value="app.variantId" label="Вариант для выгрузки" size="sm" @update:model-value="pickVariant"
          :options="variants.map((x: any) => ({value: x.variant_id, label: `${x.variant_id}${x.rank === 1 ? ' · лучший' : ''}`, icon: x.rank === 1 ? Trophy : undefined}))" />
      </div>
      <ul class="space-y-2">
        <li v-for="f in files" :key="f.title" class="card flex items-start gap-3 p-3.5" :class="f.main ? 'border-[color-mix(in_srgb,var(--accent),transparent_60%)]' : ''">
          <div class="flex size-10 shrink-0 items-center justify-center rounded-2xl" :class="f.main ? 'bg-accent-soft text-accent' : 'bg-surface-3 text-ink-2'">
            <component :is="f.icon" class="size-5" aria-hidden="true" />
          </div>
          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-1.5">
              <span class="text-[14px] font-semibold">{{ f.title }}</span>
              <span class="text-xs text-ink-3">{{ f.ext }}</span>
              <UiBadge v-if="f.main" tone="accent">основной</UiBadge>
            </div>
            <p class="mt-0.5 text-[12.5px] leading-snug text-ink-3">{{ f.purpose }}<template v-if="(sizeOf(f) ?? -1) > 0"> · {{ bytes(sizeOf(f)) }}</template></p>
            <div class="mt-2 flex flex-wrap gap-1.5">
              <UiButton size="sm" :variant="f.main ? 'primary' : 'secondary'" :icon="Download" :href="f.url" :download="f.name">Скачать</UiButton>
              <UiButton v-if="f.view" size="sm" variant="ghost" :icon="ExternalLink" :href="f.view" target="_blank">Открыть</UiButton>
            </div>
          </div>
        </li>
      </ul>
      <p class="px-1 text-xs text-ink-3">Служебные слои карты (расход существующей сети, предельная длина) в выгрузку не входят.</p>
    </template>
  </PanelLayout>
</template>
