<script setup lang="ts">
// Result, tab «Сравнение» — two variants side by side: of one calculation or of calculations with different
// parameters and versions of the data. What differs in the input is shown first, so a difference in the result can
// be explained by it.

import {computed, onBeforeUnmount, ref, watch} from 'vue';
import {useRoute, useRouter} from 'vue-router';
import {ArrowLeftRight, Calculator, Database, ExternalLink, GitCompareArrows, Map as MapIcon, TriangleAlert} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {api} from '@/api/client';
import {fmt, metres, when} from '@/lib/format';
import {toast} from '@/lib/toast';
import {addLayer, bboxOf, cssVar, defineGroup, dropGroups, fit, removeLayers, removeSources, setData} from '@/map/engine';
import PanelLayout from '@/components/shell/PanelLayout.vue';
import ResultTabs from '@/components/shell/ResultTabs.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiSection from '@/components/ui/UiSection.vue';
import UiSelect from '@/components/ui/UiSelect.vue';
import UiEmpty from '@/components/ui/UiEmpty.vue';
import UiIconButton from '@/components/ui/UiIconButton.vue';

const app = useApp();
const router = useRouter();
const route = useRoute();
const family = ref<any[] | null>(null);
const result = ref<any>(null);
const loading = ref(false);
let resultKey: string | null = null;

const a = computed(() => (route.params.a as string) || null);
const b = computed(() => (route.params.b as string) || null);

const options = computed(() => {
  const out: {value: string; label: string; hint: string}[] = [];
  for (const r of family.value || []) {
    if (r.status !== 'DONE') continue;
    for (let i = 1; i <= (r.variants || 1); i++) {
      out.push({value: `${r.id}:${i}`, label: `${r.name || 'Расчёт'} · вариант ${i}`, hint: `${r.dataset_version > 1 ? `версия ${r.dataset_version} · ` : ''}${when(r.created_at)}`});
    }
  }
  return out;
});

async function loadFamily() {
  if (!app.datasetId) return;
  family.value = await app.loadFamilyRuns(app.datasetId);
  defaults();
}

/** Without a second variant: another variant of the same calculation; otherwise the newest other calculation. */
function defaults() {
  const opts = options.value;
  if (!opts.length) return;
  const va = a.value || opts[0].value;
  let vb = b.value;
  if (!vb) {
    const [ra] = va.split(':');
    const sameRunOther = opts.find((o) => o.value.startsWith(ra + ':') && o.value !== va);
    const otherRun = opts.find((o) => !o.value.startsWith(ra + ':'));
    vb = (sameRunOther || otherRun || opts[0]).value;
  }
  if (va !== a.value || vb !== b.value) router.replace({name: 'compare', params: {ds: app.datasetId, a: va, b: vb}});
}

watch(() => app.datasetId, () => { family.value = null; loadFamily().catch((e) => toast(e.message, true)); }, {immediate: true});

watch([a, b], async ([va, vb]) => {
  if (!va || !vb) return;
  const key = va + '|' + vb;
  if (resultKey === key) return;
  resultKey = key;
  result.value = null;
  loading.value = true;
  try {
    const [cmp, fc] = await Promise.all([api.compare(va, vb), api.compareFeatures(va, vb)]);
    if (resultKey !== key) return;
    result.value = cmp;
    showOnMap(fc);
  } catch (e: any) {
    toast('Сравнение не удалось: ' + e.message, true);
  } finally {
    loading.value = false;
  }
}, {immediate: true});

function showOnMap(fc: any) {
  const colA = cssVar('--cmp-a');
  const colB = cssVar('--cmp-b');
  const colC = cssVar('--cmp-common');
  setData('cmp', fc);
  const side = ['get', 'side'];
  const color = ['match', side, 'only_a', colA, 'only_b', colB, colC];
  addLayer({id: 'cmp-line', type: 'line', source: 'cmp', filter: ['==', ['geometry-type'], 'LineString'], layout: {'line-cap': 'round', 'line-join': 'round'},
    paint: {'line-color': color, 'line-width': ['match', side, 'common', 4, 5], 'line-opacity': ['match', side, 'common', 0.7, 0.95]}});
  addLayer({id: 'cmp-multi', type: 'line', source: 'cmp', filter: ['==', ['geometry-type'], 'MultiLineString'], layout: {'line-cap': 'round', 'line-join': 'round'},
    paint: {'line-color': color, 'line-width': ['match', side, 'common', 4, 5]}});
  addLayer({id: 'cmp-tie', type: 'circle', source: 'cmp', filter: ['==', ['get', 'kind'], 'tie_in'],
    paint: {'circle-radius': 6, 'circle-color': color, 'circle-stroke-color': cssVar('--surface-solid'), 'circle-stroke-width': 2}});
  defineGroup('cmp-common', {section: 'overlay', title: 'Общая новая сеть', layers: ['cmp-line', 'cmp-multi', 'cmp-tie'], sw: colC, kind: 'line'});
  defineGroup('cmp-a', {section: 'overlay', title: 'Только в А', layers: [], sw: colA, kind: 'line', display: true});
  defineGroup('cmp-b', {section: 'overlay', title: 'Только в Б', layers: [], sw: colB, kind: 'line', display: true});
  const bb = bboxOf(fc.features);
  if (bb) fit(bb);
}

onBeforeUnmount(() => {
  removeLayers('cmp-');
  removeSources('cmp');
  dropGroups('cmp');
});

const pick = (side: 'a' | 'b', value: string) => router.push({name: 'compare', params: {ds: app.datasetId, a: side === 'a' ? value : a.value, b: side === 'b' ? value : b.value}});
const swap = () => router.push({name: 'compare', params: {ds: app.datasetId, a: b.value, b: a.value}});
const open = (s: any) => router.push({name: 'result', params: {ds: s.dataset_id, run: s.run_id, v: s.variant_id}});

const fmtMetric = (m: any, x: number) => (m.unit === 'руб.' ? fmt(x / 1e6, 1) : m.key === 'score' ? fmt(x, 2) : m.unit === 'м' ? fmt(x, 0) : fmt(x, Number.isInteger(x) ? 0 : 2));
const [runA, varA] = [computed(() => a.value?.split(':')[0]), computed(() => a.value?.split(':')[1])];
</script>

<template>
  <PanelLayout title="Сравнение вариантов" eyebrow="Шаг 4 · Результат"
    :back="{to: {name: 'calc', params: {ds: app.datasetId}, query: {tab: 'history'}}, label: 'История расчётов'}">
    <template #header><ResultTabs :run-a="runA" :variant-a="varA" /></template>

    <div v-if="family == null" class="space-y-3"><div class="skeleton h-32" /><div class="skeleton h-48" /></div>
    <UiEmpty v-else-if="!options.length" :icon="GitCompareArrows" title="Нечего сравнивать" text="Для сравнения нужен хотя бы один завершённый расчёт.">
      <UiButton :icon="Calculator" @click="router.push({name: 'calc', params: {ds: app.datasetId}})">К расчёту</UiButton>
    </UiEmpty>

    <template v-else>
      <!-- pickers -->
      <div class="card space-y-2 p-3">
        <div class="flex items-center gap-2">
          <span class="flex size-7 shrink-0 items-center justify-center rounded-full bg-cmp-a text-xs font-bold text-white" aria-hidden="true">А</span>
          <div class="min-w-0 flex-1"><UiSelect :model-value="a || ''" :options="options" label="Вариант А" @update:model-value="pick('a', $event)" /></div>
        </div>
        <div class="flex items-center gap-2">
          <span class="flex size-7 shrink-0 items-center justify-center rounded-full bg-cmp-b text-xs font-bold text-white" aria-hidden="true">Б</span>
          <div class="min-w-0 flex-1"><UiSelect :model-value="b || ''" :options="options" label="Вариант Б" @update:model-value="pick('b', $event)" /></div>
          <UiIconButton :icon="ArrowLeftRight" label="Поменять местами" variant="secondary" @click="swap" />
        </div>
      </div>

      <div v-if="!result" class="space-y-3"><div class="skeleton h-24" /><div class="skeleton h-56" /></div>
      <template v-else>
        <p class="px-0.5 text-[14px] leading-relaxed text-ink">{{ result.explanation }}</p>

        <!-- metrics -->
        <section class="card p-3.5" aria-labelledby="cmp-m">
          <h2 id="cmp-m" class="mb-2 text-[14px] font-semibold">Показатели</h2>
          <table class="tbl">
            <thead><tr><th /><th class="r"><span class="text-cmp-a">А</span></th><th class="r"><span class="text-cmp-b">Б</span></th><th class="r">Б − А</th></tr></thead>
            <tbody>
              <tr v-for="m in result.metrics" :key="m.key" :class="m.title[0] === m.title[0].toLowerCase() ? 'text-ink-3' : ''">
                <td :class="m.title[0] === m.title[0].toLowerCase() ? 'pl-3 text-xs' : ''">{{ m.title }}{{ m.unit === 'руб.' && m.title[0] !== m.title[0].toLowerCase() ? ', млн ₽' : m.unit === 'м' ? ', м' : '' }}</td>
                <td class="r" :class="m.better === 'a' ? 'font-semibold text-ok' : ''">{{ fmtMetric(m, m.a) }}</td>
                <td class="r" :class="m.better === 'b' ? 'font-semibold text-ok' : ''">{{ fmtMetric(m, m.b) }}</td>
                <td class="r" :class="m.better === 'b' ? 'text-ok' : m.better === 'a' ? 'text-bad' : 'text-ink-3'">{{ m.delta === 0 ? '—' : (m.delta > 0 ? '+' : '−') + fmtMetric(m, Math.abs(m.delta)) }}</td>
              </tr>
            </tbody>
          </table>
          <p class="mt-2 text-xs text-ink-3">Зелёным — лучше (меньше).</p>
        </section>

        <!-- geometry -->
        <section class="card p-3.5" aria-labelledby="cmp-g">
          <h2 id="cmp-g" class="mb-2 flex items-center gap-2 text-[14px] font-semibold"><MapIcon class="size-4 text-ink-3" aria-hidden="true" />На карте</h2>
          <div class="grid grid-cols-3 gap-2 text-center">
            <div class="inset px-2 py-2"><div class="mx-auto mb-1 h-1 w-6 rounded-full bg-cmp-common" /><div class="num text-[15px] font-semibold">{{ metres(result.geometry.common_length) }}</div><div class="text-xs text-ink-3">общее</div></div>
            <div class="inset px-2 py-2"><div class="mx-auto mb-1 h-1 w-6 rounded-full bg-cmp-a" /><div class="num text-[15px] font-semibold">{{ metres(result.geometry.only_a_length) }}</div><div class="text-xs text-ink-3">только А</div></div>
            <div class="inset px-2 py-2"><div class="mx-auto mb-1 h-1 w-6 rounded-full bg-cmp-b" /><div class="num text-[15px] font-semibold">{{ metres(result.geometry.only_b_length) }}</div><div class="text-xs text-ink-3">только Б</div></div>
          </div>
          <p class="mt-2 text-xs text-ink-3">Совпадение с точностью {{ fmt(result.geometry.tolerance_m) }} м. Точки — врезки.</p>
        </section>

        <!-- input -->
        <UiSection title="Различия исходных данных" :icon="Database" persist="cmp.input" :meta="result.params.length ? `параметров: ${result.params.length}` : 'одинаковые параметры'">
          <p v-if="result.data.same_dataset" class="text-[13px] text-ink-2">Одна версия набора.</p>
          <div v-else-if="result.data.same_family" class="space-y-1 text-[13px] text-ink-2">
            <p>Версии {{ result.a.dataset_version }} и {{ result.b.dataset_version }} одного набора.</p>
            <p v-if="result.data.edits_only_a.length"><b>Правки только в А:</b> {{ result.data.edits_only_a.map((e: any) => `${e.title}, ${fmt(e.area_m2)} м²`).join('; ') }}</p>
            <p v-if="result.data.edits_only_b.length"><b>Правки только в Б:</b> {{ result.data.edits_only_b.map((e: any) => `${e.title}, ${fmt(e.area_m2)} м²`).join('; ') }}</p>
          </div>
          <p v-else class="flex items-start gap-2 text-[13px] text-warn"><TriangleAlert class="mt-0.5 size-4 shrink-0" aria-hidden="true" />Разные наборы данных — сравнение результатов условно.</p>
          <table v-if="result.params.length" class="tbl mt-2">
            <thead><tr><th>Параметр</th><th>А</th><th>Б</th></tr></thead>
            <tbody><tr v-for="p in result.params" :key="p.title"><td>{{ p.title }}</td><td>{{ p.a }}</td><td>{{ p.b }}</td></tr></tbody>
          </table>
          <p v-else class="mt-1 text-[13px] text-ink-3">Параметры расчёта одинаковы.</p>
        </UiSection>

        <!-- sides -->
        <div class="grid grid-cols-2 gap-2">
          <button v-for="[label, s] in [['А', result.a], ['Б', result.b]] as const" :key="label" type="button" @click="open(s)"
            class="card group p-3 text-left transition-colors hover:border-line-strong">
            <div class="flex items-center gap-1.5 text-[13px] font-semibold"><span :class="label === 'А' ? 'text-cmp-a' : 'text-cmp-b'">{{ label }}</span> · вариант {{ s.variant_id }}
              <UiBadge class="ml-auto" tone="outline">{{ s.rank }} место</UiBadge></div>
            <div class="mt-1 line-clamp-2 text-xs text-ink-3">{{ s.name }} · {{ s.strategy === 'separate' ? 'раздельное' : 'совместное' }}</div>
            <div class="mt-2 inline-flex items-center gap-1 text-xs font-medium text-accent-ink">Открыть <ExternalLink class="size-3" aria-hidden="true" /></div>
          </button>
        </div>
      </template>
    </template>
  </PanelLayout>
</template>
