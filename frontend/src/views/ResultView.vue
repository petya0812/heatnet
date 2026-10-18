<script setup lang="ts">
// Step 4 — result: the variants of a calculation in words and figures, the structure of the cost, the check of the
// rules, the OKS list, the load of the existing network and the length limit; the card of one OKS (path, why this
// tie-in, how the route was searched). The main action is the download, next to it — the replay and the comparison.

import {computed, ref, watch} from 'vue';
import {RouterLink, useRouter} from 'vue-router';
import {ArrowRight, Check, CircleAlert, Clapperboard, Download, Ellipsis, GitCompareArrows, Lightbulb, MapPin, Merge, Pencil, Pin, PinOff,
  Route, Ruler, Search, SlidersHorizontal, ChevronLeft, ChevronRight, ShieldAlert, ShieldCheck, StickyNote, TriangleAlert, Trophy, Wrench, X, CircleDot, ListTree, Building2} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {api} from '@/api/client';
import {ago, cap, count, fmt, isActive, metres, mln, natural, rub, splitName} from '@/lib/format';
import {HINT_ACTIONS, HINTS, SPECIAL_TITLES, TARGET_TITLES} from '@/lib/terms';
import {paramDiff} from '@/lib/catalog';
import {COSTS, derive} from '@/lib/variant';
import {toast} from '@/lib/toast';
import {promptDialog} from '@/lib/dialogs';
import {clearHighlight, highlight} from '@/map/engine';
import * as player from '@/map/player';
import PanelLayout from '@/components/shell/PanelLayout.vue';
import ResultTabs from '@/components/shell/ResultTabs.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiSection from '@/components/ui/UiSection.vue';
import UiStat from '@/components/ui/UiStat.vue';
import UiEmpty from '@/components/ui/UiEmpty.vue';
import UiMenu from '@/components/ui/UiMenu.vue';
import UiIconButton from '@/components/ui/UiIconButton.vue';
import UiTooltip from '@/components/ui/UiTooltip.vue';
import UiTerm from '@/components/ui/UiTerm.vue';
import UiSegmented from '@/components/ui/UiSegmented.vue';
import UiProgress from '@/components/ui/UiProgress.vue';
import UiExpandRow from '@/components/ui/UiExpandRow.vue';

const app = useApp();
const router = useRouter();
const run = computed(() => app.currentRun);
const variants = computed(() => (run.value?.summary || []).slice().sort((a: any, b: any) => a.rank - b.rank));
const v = computed(() => variants.value.find((x: any) => x.variant_id === app.variantId) || variants.value[0]);
const d = computed(() => derive(app.variantData));
const diff = computed(() => paramDiff(app.catalog, run.value?.params));
const allParams = ref(false);
const datasetTitle = computed(() => (run.value ? splitName(run.value.dataset_name).title : ''));

const goVariant = (id: string) => router.push({name: 'result', params: {ds: app.datasetId, run: app.runId, v: id}});
const goOks = (id: string) => router.push({name: 'oks', params: {ds: app.datasetId, run: app.runId, v: app.variantId, oks: id}});
const back = () => { clearHighlight(); router.push({name: 'result', params: {ds: app.datasetId, run: app.runId, v: app.variantId}}); };

// ------------------------------------------------------------------ the run: name, note, pin

async function patchRun(p: Record<string, unknown>) {
  try {
    await api.updateRun(app.runId!, p);
    await app.loadRun(app.runId!);
    app.loadRuns(app.datasetId!);
  } catch (e: any) { toast(e.message, true); }
}
const runMenu = computed(() => [
  {label: 'Изменить параметры и пересчитать', icon: SlidersHorizontal, onSelect: () => router.push({name: 'calc', params: {ds: app.datasetId}, query: {from: app.runId}})},
  {label: 'Переименовать', icon: Pencil, onSelect: async () => { const n = await promptDialog('Название расчёта', {value: run.value.name || ''}); if (n != null) patchRun({name: n}); }},
  {label: run.value?.note ? 'Изменить заметку' : 'Добавить заметку', icon: StickyNote, onSelect: async () => {
    const n = await promptDialog('Заметка к расчёту', {value: run.value.note || '', multiline: true, placeholder: 'Цель расчёта, что проверяем'});
    if (n != null) patchRun({note: n});
  }},
  {label: run.value?.pinned ? 'Открепить' : 'Закрепить', icon: run.value?.pinned ? PinOff : Pin, onSelect: () => patchRun({pinned: !run.value.pinned})},
]);

// ------------------------------------------------------------------ figures

const costTotal = computed(() => COSTS.reduce((a, c) => a + (v.value?.[c.key] || 0), 0) || 1);
const costs = computed(() => COSTS.filter((c) => v.value?.[c.key] > 0).map((c) => ({...c, value: v.value[c.key], pct: (100 * v.value[c.key]) / costTotal.value})));
const val = computed(() => run.value?.validation || {});
const rulesChecked = computed(() => (val.value.checked ? Object.keys(val.value.checked).length : 0));
const shortfalls = computed(() => (app.variantData ? d.value.recon.length : v.value?.capacity_shortfalls || 0));

// ------------------------------------------------------------------ OKS list

const filter = ref<'all' | 'unconnected'>('all');
const oksQuery = ref('');
const showAllOks = ref(false);
const allOks = computed(() => (v.value?.oks || []).slice().sort((a: any, b: any) => (a.connected === b.connected ? natural(a.oks_id, b.oks_id) : a.connected ? 1 : -1)));
const unconnected = computed(() => allOks.value.filter((o: any) => !o.connected));
const oksList = computed(() => {
  const q = oksQuery.value.trim().toLowerCase();
  return allOks.value.filter((o: any) => (filter.value === 'all' || !o.connected) && (!q || String(o.oks_id).toLowerCase().includes(q)));
});
const oksShown = computed(() => (showAllOks.value || oksQuery.value ? oksList.value : oksList.value.slice(0, 30)));
const oksSection = ref<HTMLElement | null>(null);
function onlyUnconnected() {
  filter.value = 'unconnected';
  showAllOks.value = true;
  setTimeout(() => oksSection.value?.scrollIntoView({block: 'start', behavior: 'smooth'}), 30);
}
const joinsText = (o: any) => (o.joins === 'tie_in'
  ? `своя врезка ${o.tie_object_type === 'heat_chamber' ? 'в камеру' : 'в участок'} ${o.tie_object_id}`
  : `от разветвления, врезка ${o.tie_object_id}`);

// ------------------------------------------------------------------ the card of an OKS

const oks = computed(() => (app.oksId ? (v.value?.oks || []).find((x: any) => x.oks_id === app.oksId) : null));
const oksIndex = computed(() => allOks.value.findIndex((x: any) => x.oks_id === app.oksId));
const journal = ref<any>(null);
let journalFor: string | null = null;
async function ensureJournal() {
  if (!app.runId || !run.value || run.value.status !== 'DONE') return null;
  const key = app.runId + '|' + app.variantId;
  if (journalFor === key && journal.value) return journal.value;
  journalFor = key;
  journal.value = null;
  try {
    journal.value = await app.loadJournal(app.runId, app.variantId);
  } catch (e) {
    journal.value = {events: []};
  }
  return journal.value;
}
watch(() => app.oksId && run.value?.status, (x) => { if (x) ensureJournal(); }, {immediate: true});

const oksDetail = computed(() => {
  const o = oks.value;
  if (!o || !o.connected) return null;
  const byId = new Map<string, any>();
  for (const f of app.variantData?.features || []) if (f.properties.id) byId.set(f.properties.id, f);
  const segs = o.path_segment_ids.map((id: string) => byId.get(id)).filter(Boolean);
  const chain = new Set(o.existing_chain);
  const recon = d.value.flows.filter((f: any) => chain.has(f.properties.existing_object_id) && f.properties.reconstructed);
  const ev = journal.value ? journal.value.events.filter((e: any) => (e.type === 'connect' || e.type === 'rebuild') && e.oks === o.oks_id && e.route).pop() : null;
  const alts = ev ? ev.alternatives || [] : [];
  const best = alts.length ? Math.min(...alts.map((a: any) => a.score)) : 0;
  const worst = alts.length ? Math.max(...alts.map((a: any) => a.score)) : 0;
  return {segs, recon, ev, alts, best, worst};
});

/** The features of the route of one OKS (or its outline when it has none). */
function pathFeatures(id: string) {
  const o = (v.value?.oks || []).find((x: any) => x.oks_id === id);
  if (!o || !app.variantData) return null;
  const ids = new Set(o.connected ? o.path_segment_ids.concat([o.tie_in_id]) : []);
  return app.variantData.features.filter((f: any) => (f.properties.id && ids.has(f.properties.id))
    || (!o.connected && f.properties.object_type === 'unconnected_oks' && f.properties.oks_id === id && f.geometry));
}
// a row of the list shows its route on the map while the pointer is over it; the map does not move
const hoverOks = (id: string) => { const f = pathFeatures(id); if (f && f.length) highlight({type: 'FeatureCollection', features: f}, {fitTo: false}); };
const unhoverOks = () => clearHighlight();

function showPath(id: string) {
  const o = (v.value?.oks || []).find((x: any) => x.oks_id === id);
  if (!o || !app.variantData) return;
  const ids = new Set(o.connected ? o.path_segment_ids.concat([o.tie_in_id]) : []);
  const feats = app.variantData.features.filter((f: any) => (f.properties.id && ids.has(f.properties.id))
    || (!o.connected && f.properties.object_type === 'unconnected_oks' && f.properties.oks_id === id && f.geometry));
  if (feats.length) highlight({type: 'FeatureCollection', features: feats}, {maxZoom: o.connected ? 17 : 16});
}
// the card shows its OKS on the map as soon as the variant is there
watch(() => [app.oksId, app.variantData], () => { if (app.oksId) setTimeout(() => showPath(app.oksId!), 200); }, {immediate: true});

function showSeg(id: string) {
  const f = app.variantData?.features.find((x: any) => x.properties.id === id);
  if (f) highlight({type: 'FeatureCollection', features: [f]}, {maxZoom: 18});
}

async function replay(filterOks?: string) {
  const j = await ensureJournal();
  if (j && j.events.length) player.openBuild(j.events, {title: filterOks ? `Построение · ОКС ${filterOks}` : `Построение · Вариант ${app.variantId}`, filterOks: filterOks || null});
  else toast('Журнал построения пуст');
}
const searching = ref(false);
async function searchTrace(id: string) {
  searching.value = true;
  try {
    const trace = await api.search(app.runId!, id);
    player.openSearch(trace, {title: `Поиск трассы · ОКС ${id}`, subtitle: 'до существующей сети, без других ОКС варианта'});
  } catch (e: any) {
    toast('Не удалось: ' + e.message, true);
  } finally {
    searching.value = false;
  }
}

/** The way from a reason to its remedy: the setting with the parameters of this run, or the check of the data. */
const hintAction = computed(() => {
  const a = oks.value && !oks.value.connected ? HINT_ACTIONS[oks.value.reason] : null;
  if (!a) return null;
  const entry = a.param && app.catalog ? app.catalog.params.find((p: any) => p.key === a.param && p.editable) : null;
  if (entry) return {label: `Настройка «${entry.title}»`, go: () => router.push({name: 'calc', params: {ds: app.datasetId}, query: {from: app.runId, param: entry.key}})};
  if (a.check) return {label: 'К проверке данных', go: () => router.push({name: 'check', params: {ds: app.datasetId}})};
  return null;
});

const toExport = () => router.push({name: 'export', params: {ds: app.datasetId, run: app.runId, v: app.variantId}});
const toCompare = () => router.push({name: 'compare', params: {ds: app.datasetId, a: `${app.runId}:${app.variantId}`}});
</script>

<template>
  <!-- ============================================================ the card of an OKS -->
  <PanelLayout v-if="app.oksId && run && run.status === 'DONE'" :title="`ОКС ${app.oksId}`" :eyebrow="`Вариант ${app.variantId} · карточка ОКС`"
    :back="{to: {name: 'result', params: {ds: app.datasetId, run: app.runId, v: app.variantId}}, label: `К варианту ${app.variantId}`}">
    <template #title-extra>
      <UiBadge v-if="oks" :tone="oks.connected ? 'ok' : 'bad'" size="md" dot>{{ oks.connected ? 'подключён' : 'не подключён' }}</UiBadge>
    </template>
    <template #sub>
      <div v-if="oksIndex >= 0" class="mt-2 flex items-center gap-1">
        <UiIconButton :icon="ChevronLeft" label="Предыдущий ОКС" size="sm" variant="secondary" :disabled="oksIndex <= 0" @click="goOks(allOks[oksIndex - 1].oks_id)" />
        <span class="num px-1 text-[13px] text-ink-3">{{ oksIndex + 1 }} из {{ allOks.length }}</span>
        <UiIconButton :icon="ChevronRight" label="Следующий ОКС" size="sm" variant="secondary" :disabled="oksIndex >= allOks.length - 1" @click="goOks(allOks[oksIndex + 1].oks_id)" />
      </div>
    </template>

    <UiEmpty v-if="!oks" :icon="MapPin" title="ОКС не найден в этом варианте" :text="app.oksId" />

    <template v-else-if="!oks.connected">
      <div class="flex items-start gap-2.5 rounded-2xl bg-bad-soft p-3.5 text-[13.5px] text-bad">
        <CircleAlert class="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        <span>{{ cap(oks.reason_text) }}{{ oks.detail ? ` (${oks.detail})` : '' }}</span>
      </div>
      <div class="grid grid-cols-2 gap-2">
        <UiStat label="Расход" :value="fmt(oks.flow_tph, 2)" unit="т/ч" />
        <UiStat label="Штраф в стоимости" :value="mln(100e6 + 0.5e6 * (oks.flow_tph || 0))" unit="млн ₽" tone="bad" />
      </div>
      <div v-if="HINTS[oks.reason]" class="card flex items-start gap-2.5 p-3.5 text-[13px] text-ink-2">
        <Lightbulb class="mt-0.5 size-4 shrink-0 text-warn" aria-hidden="true" />
        <div class="min-w-0">
          <b class="text-ink">Что можно сделать.</b> {{ HINTS[oks.reason] }}
          <div v-if="hintAction" class="mt-2">
            <UiButton size="sm" variant="soft" :icon-right="ArrowRight" @click="hintAction.go()">{{ hintAction.label }}</UiButton>
          </div>
        </div>
      </div>
    </template>

    <template v-else>
      <p class="text-[14px] leading-relaxed text-ink">
        {{ joinsText(oks)[0].toUpperCase() + joinsText(oks).slice(1) }} · трасса {{ metres(oks.own_length) }} · ДУ {{ oks.diameter }} · поворотов {{ oks.turns }}
        <template v-if="oks.specials.length"> · спецпроход: {{ oks.specials.map((x: string) => SPECIAL_TITLES[x] || x).join(', ') }}</template>
      </p>
      <div class="grid grid-cols-2 gap-2">
        <UiStat label="Расход" :value="fmt(oks.flow_tph, 2)" unit="т/ч" />
        <UiStat label="Стоимость своей ветки" :value="mln(oks.own_cost, 2)" unit="млн ₽" />
        <UiStat label="Путь от врезки" :value="metres(oks.path_length)" />
        <UiStat label="Внутри здания" :value="metres(oks.inside_building_m)" :hint="oks.inside_building_m > oks.inside_building_min_m + 0.05 ? `Не от ближайшей стены: +${metres(oks.inside_building_m - oks.inside_building_min_m)}` : 'Ввод от ближайшей к точке стены'" />
      </div>
      <div v-if="oks.shared_with.length" class="text-[13px] text-ink-2">
        <span class="text-ink-3">Общая сеть с ОКС:</span>
        <span class="ml-1 inline-flex flex-wrap gap-1 align-middle">
          <button v-for="x in oks.shared_with.slice().sort(natural)" :key="x" type="button" class="h-8 min-w-8 rounded-lg bg-surface-3 px-2 text-[13px] font-medium text-ink-2 hover:bg-accent-soft hover:text-accent-ink" :aria-label="'ОКС ' + x" @click="goOks(x)">{{ x }}</button>
        </span>
      </div>

      <UiSection title="Путь от источника" :icon="Route" persist="oks.path">
        <p class="text-[13px] text-ink-2">
          Существующая сеть: {{ count(oks.existing_chain.length, 'участок', 'участка', 'участков') }} до врезки {{ oks.tie_object_id }}.
          {{ oksDetail?.recon.length ? `${count(oksDetail.recon.length, 'участку не хватит', 'участкам не хватит', 'участкам не хватит')} пропускной способности.` : 'Пропускной способности существующей сети хватает.' }}
        </p>
        <table v-if="oksDetail?.recon.length" class="tbl mt-2">
          <thead><tr><th>Участок</th><th class="r">Расход, т/ч</th><th class="r">ДУ</th></tr></thead>
          <tbody><tr v-for="f in oksDetail.recon" :key="f.properties.existing_object_id">
            <td>{{ f.properties.existing_object_id }}</td>
            <td class="r">{{ fmt(f.properties.existing_flow_tph, 1) }} → {{ fmt(f.properties.calculated_flow_tph, 1) }}</td>
            <td class="r">{{ f.properties.existing_diameter }} → <b class="text-bad">{{ f.properties.required_diameter }}</b></td>
          </tr></tbody>
        </table>
        <div class="eyebrow mb-1 mt-4">Новые участки от врезки к ОКС</div>
        <table class="tbl">
          <thead><tr><th>Участок</th><th class="r">ДУ</th><th class="r">т/ч</th><th class="r">Длина</th><th class="r"><UiTerm term="предельная длина" text="Предел" /></th></tr></thead>
          <tbody>
            <tr v-for="f in oksDetail?.segs" :key="f.properties.id" class="click" tabindex="0" @click="showSeg(f.properties.id)" @keydown.enter="showSeg(f.properties.id)">
              <td class="text-[13px]">
                {{ f.properties.id.replace(/^v\d+-seg-/, 'участок ').replace(/^v\d+-/, '') }}
                <UiBadge v-if="f.properties.laying_method === 'special'" tone="warn" class="ml-1">спецпроход ×{{ fmt(f.properties.k, 2) }}</UiBadge>
              </td>
              <td class="r">{{ f.properties.diameter }}</td>
              <td class="r">{{ fmt(f.properties.flow_tph, 1) }}</td>
              <td class="r">{{ fmt(f.properties.length, 0) }} м</td>
              <td class="r">{{ d.usageOf[f.properties.id] ? fmt(100 * (d.usageOf[f.properties.id].usage || 0)) + ' %' : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </UiSection>

      <UiSection title="Выбор места врезки" :icon="Merge" persist="oks.alts">
        <p v-if="!journal" class="text-[13px] text-ink-3">Загрузка журнала…</p>
        <p v-else-if="!oksDetail?.ev" class="text-[13px] text-ink-3">В журнале построения нет шага этого ОКС.</p>
        <p v-else-if="oksDetail.alts.length < 2" class="text-[13px] text-ink-2">Другие места врезки в радиусе поиска недопустимы или заведомо дороже.</p>
        <template v-else>
          <p class="mb-2 text-[12.5px] text-ink-3">Оценка места — его вклад в <UiTerm term="оценка S" text="оценку S" />: трасса плюс камера или врезка. Выбрано наименьшее.</p>
          <div v-for="(a, i) in oksDetail.alts" :key="i" class="flex items-center gap-2.5 py-1.5">
            <span class="flex size-5 shrink-0 items-center justify-center rounded-full" :class="a.chosen ? 'bg-ok text-white' : 'bg-surface-3 text-ink-4'">
              <Check v-if="a.chosen" class="size-3" stroke-width="3" aria-label="выбрано" />
            </span>
            <div class="min-w-0 flex-1">
              <div class="truncate text-[13px]" :class="a.chosen ? 'font-semibold text-ink' : 'text-ink-2'">{{ (TARGET_TITLES[a.kind] || a.kind) + (a.object_id ? ' ' + a.object_id : '') }}</div>
              <div class="mt-1 h-1.5 overflow-hidden rounded-full bg-surface-3">
                <div class="h-full rounded-full" :class="a.chosen ? 'bg-ok' : 'bg-ink-4'"
                  :style="{width: Math.max(6, 100 * (1 - (a.score - oksDetail.best) / Math.max(1e-9, oksDetail.worst - oksDetail.best + 0.01))) + '%'}" />
              </div>
            </div>
            <UiTooltip :content="a.estimate ? 'Не досчитывалось: даже прямая линия до этого места дороже выбранного' : undefined">
              <span class="num w-[88px] shrink-0 text-right text-[13px]" :class="a.chosen ? 'font-semibold text-ok' : 'text-ink-2'">
                {{ (a.estimate ? '≥ ' : '') + fmt(a.score, 2) }}<span v-if="a.score - oksDetail.best >= 0.005" class="text-xs text-ink-3"> +{{ fmt(a.score - oksDetail.best, 2) }}</span>
              </span>
            </UiTooltip>
          </div>
        </template>
      </UiSection>
    </template>

    <template #footer>
      <UiButton v-if="oks" size="sm" variant="secondary" :icon="MapPin" @click="showPath(oks.oks_id)">На карте</UiButton>
      <UiButton v-if="oks" size="sm" variant="secondary" :icon="Search" :loading="searching" @click="searchTrace(oks.oks_id)">Поиск трассы</UiButton>
      <UiButton v-if="oks" size="sm" variant="secondary" :icon="Clapperboard" @click="replay(oks.oks_id)">Построение</UiButton>
    </template>
  </PanelLayout>

  <!-- ============================================================ the variant -->
  <PanelLayout v-else :title="run ? run.name || 'Расчёт' : 'Результат'" eyebrow="Шаг 4 · Результат"
    :back="{to: {name: 'calc', params: {ds: app.datasetId}, query: {tab: 'history'}}, label: 'К истории расчётов'}">
    <template #title-extra>
      <Pin v-if="run?.pinned" class="size-4 shrink-0 text-ink-3" aria-label="Закреплён" />
      <UiMenu v-if="run" :items="runMenu"><UiIconButton :icon="Ellipsis" label="Действия с расчётом" size="sm" /></UiMenu>
    </template>
    <template #sub>
      <div v-if="run" class="mt-1 text-[13px] text-ink-3">{{ datasetTitle }}<template v-if="run.dataset_version > 1"> · версия {{ run.dataset_version }}</template> · {{ ago(run.created_at) }}</div>
      <p v-if="run?.note" class="mt-1 text-[12.5px] italic text-ink-2">{{ run.note }}</p>
      <div v-if="run" class="mt-2 flex flex-wrap gap-1">
        <UiBadge v-if="!diff.length" tone="neutral">Параметры по умолчанию</UiBadge>
        <UiTooltip v-for="p in (allParams ? diff : diff.slice(0, 2))" :key="p.key" :content="p.title">
          <span class="inline-flex h-[22px] max-w-full items-center truncate rounded-full bg-accent-soft px-2 text-xs font-medium text-accent-ink" tabindex="0">{{ p.title }}: {{ p.value }}</span>
        </UiTooltip>
        <button v-if="diff.length > 2" type="button" class="inline-flex h-[22px] items-center rounded-full px-2 text-xs font-medium text-ink-2 hover:bg-surface-3"
          :aria-expanded="allParams" @click="allParams = !allParams">{{ allParams ? 'Свернуть' : `ещё ${diff.length - 2}` }}</button>
      </div>
    </template>
    <template #header><ResultTabs /></template>

    <div v-if="!run" class="space-y-3"><div class="skeleton h-20" /><div class="skeleton h-32" /><div class="skeleton h-48" /></div>

    <div v-else-if="run.status !== 'DONE'" class="card p-4">
      <template v-if="isActive(run.status)">
        <div class="font-semibold">Расчёт ещё идёт</div>
        <UiProgress class="mt-2" :value="run.progress?.done" :max="run.progress?.total || undefined" :label="run.progress?.stage" />
        <UiButton class="mt-3" size="sm" @click="router.push({name: 'calc', params: {ds: app.datasetId}})">Показать ход расчёта</UiButton>
      </template>
      <div v-else class="flex items-start gap-2.5 text-[13.5px] text-bad">
        <CircleAlert class="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        {{ run.status === 'FAILED' ? 'Расчёт завершился с ошибкой: ' + (run.error || '') : 'Расчёт отменён.' }}
      </div>
    </div>

    <template v-else-if="v">
      <!-- variants -->
      <div role="radiogroup" aria-label="Вариант" class="grid gap-2" :class="variants.length > 1 ? 'grid-cols-2' : 'grid-cols-1'">
        <button v-for="x in variants" :key="x.variant_id" type="button" role="radio" :aria-checked="x.variant_id === v.variant_id" @click="goVariant(x.variant_id)"
          class="relative rounded-[16px] border p-3 text-left transition-[border-color,box-shadow,background-color]"
          :class="x.variant_id === v.variant_id ? 'border-accent bg-surface-solid shadow-[0_0_0_3px_var(--accent-soft)]' : 'border-line bg-surface-2 hover:border-line-strong hover:bg-surface-solid'">
          <div class="flex items-center gap-1.5 text-[13px] font-semibold" :class="x.variant_id === v.variant_id ? 'text-accent-ink' : 'text-ink-2'">
            <Trophy v-if="x.rank === 1" class="size-3.5 text-trophy" aria-label="лучший" />Вариант {{ x.variant_id }}
            <span class="ml-auto text-xs font-medium text-ink-3">{{ x.rank }} место</span>
          </div>
          <div class="mt-1 flex items-baseline gap-1.5"><span class="text-[22px] font-semibold leading-none tracking-tight text-ink">S {{ fmt(x.score, 2) }}</span></div>
          <div class="mt-1.5 text-xs text-ink-3">{{ fmt(x.calculated_cost / 1e6, 1) }} млн ₽ · {{ x.strategy === 'separate' ? 'раздельное' : 'совместное' }}</div>
        </button>
      </div>

      <p class="-mt-1 px-0.5 text-xs text-ink-3"><UiTerm term="оценка S" text="Оценка S" /> — меньше лучше: 0,7 · стоимость / 25 млн ₽ + 0,3 · длина / 100 м.</p>
      <p class="px-0.5 text-[14px] leading-relaxed text-ink">{{ v.plain }}</p>

      <!-- check of the rules: one block — the rules, then the notes (not violations) if there are any -->
      <UiExpandRow class="card !border-b px-3" :class="val.errors ? '!border-bad/40 bg-bad-soft' : ''">
        <template #lead><component :is="val.errors ? ShieldAlert : ShieldCheck" class="size-4" :class="val.errors ? 'text-bad' : 'text-ok'" aria-hidden="true" /></template>
        <template #title>
          <span :class="val.errors ? 'font-medium text-bad' : ''">{{ val.errors ? `Нарушений правил: ${val.errors}` : `Обязательные правила выполнены${rulesChecked ? ` · ${rulesChecked}` : ''}` }}</span>
          <span v-if="val.violations?.length && !val.errors" class="text-ink-3"> · замечаний {{ val.violations.length }}</span>
        </template>
        <p class="mb-1.5 text-xs text-ink-3">Результат перечитан из файла выгрузки и проверен по обязательным правилам независимо от расчёта.</p>
        <ul v-if="val.violations?.length" class="space-y-1 text-xs">
          <li v-for="(x, i) in val.violations" :key="i" class="font-mono leading-snug">{{ x }}</li>
        </ul>
      </UiExpandRow>
      <button v-if="unconnected.length" type="button" class="flex w-full items-center gap-2 rounded-[14px] bg-warn-soft px-3 py-2.5 text-left text-[13.5px] font-medium text-warn hover:brightness-95" @click="onlyUnconnected">
        <TriangleAlert class="size-4 shrink-0" aria-hidden="true" /><span class="flex-1">Без трассы: {{ count(unconnected.length, 'ОКС', 'ОКС', 'ОКС') }} — показать в списке</span><ArrowRight class="size-4" aria-hidden="true" />
      </button>

      <!-- figures -->
      <div class="grid grid-cols-3 gap-2">
        <UiStat label="Подключено" :value="`${allOks.length - unconnected.length} из ${allOks.length}`" :tone="unconnected.length ? 'bad' : 'ok'" />
        <UiStat label="Стоимость" :value="fmt(v.calculated_cost / 1e6, 1)" unit="млн ₽" />
        <UiStat label="Новая сеть" :value="metres(v.new_network_length)" />
        <UiStat label="Врезок" :value="fmt(v.tie_ins)" :hint="`из них в существующие камеры: ${fmt(v.existing_chamber_tie_in_count)}`" />
        <UiStat label="Новых камер" :value="fmt(v.new_chambers)" :hint="`в местах врезки: ${fmt(v.new_chambers - v.branching_chambers)}, разветвлений: ${fmt(v.branching_chambers)}`" />
        <UiStat label="Не хватит ДУ" :value="fmt(shortfalls)" :tone="shortfalls ? 'bad' : undefined"
          hint="Участков существующей сети, которым не хватит пропускной способности после подключения. В стоимость не входит" />
      </div>

      <!-- cost -->
      <div class="card p-3.5">
        <div class="mb-2 flex items-baseline gap-2">
          <span class="text-[13.5px] font-semibold">Структура стоимости</span>
          <span class="num ml-auto text-[13px] text-ink-3">{{ rub(v.calculated_cost) }}</span>
        </div>
        <div class="flex h-2.5 gap-0.5 overflow-hidden rounded-full" role="img" :aria-label="costs.map((c) => `${c.title} ${fmt(c.value / 1e6, 1)} млн ₽`).join(', ')">
          <UiTooltip v-for="c in costs" :key="c.key" :content="`${c.title}: ${rub(c.value)}`">
            <span class="h-full first:rounded-l-full last:rounded-r-full" :style="{width: c.pct + '%', background: c.color}" />
          </UiTooltip>
        </div>
        <div class="mt-2.5 grid grid-cols-2 gap-x-3 gap-y-1 text-[12.5px]">
          <div v-for="c in costs" :key="c.key" class="flex items-center gap-1.5">
            <span class="size-2 shrink-0 rounded-full" :style="{background: c.color}" aria-hidden="true" />
            <span class="truncate text-ink-2">{{ c.title }}</span>
            <span class="num ml-auto text-ink">{{ fmt(c.value / 1e6, 1) }}</span>
          </div>
        </div>
      </div>

      <!-- OKS -->
      <section ref="oksSection" class="card" aria-labelledby="oks-h">
        <header class="flex items-center gap-2.5 px-4 pb-2 pt-3.5">
          <MapPin class="size-[18px] text-ink-3" aria-hidden="true" />
          <h2 id="oks-h" class="flex-1 text-[14px] font-semibold">Перспективные <UiTerm term="ОКС" /></h2>
          <span class="text-xs text-ink-3">{{ allOks.length - unconnected.length }} из {{ allOks.length }} подключены</span>
        </header>
        <div class="flex items-center gap-2 px-4 pb-2">
          <label class="flex h-8 min-w-0 flex-1 items-center gap-1.5 rounded-[10px] bg-surface-2 px-2.5">
            <Search class="size-3.5 shrink-0 text-ink-3" aria-hidden="true" />
            <input v-model="oksQuery" class="min-w-0 flex-1 bg-transparent text-[13px] outline-none placeholder:text-ink-4" placeholder="Номер ОКС" aria-label="Найти ОКС по номеру" />
          </label>
          <UiSegmented v-if="unconnected.length" v-model="filter" label="Какие ОКС показывать" size="sm"
            :options="[{value: 'all', label: 'Все'}, {value: 'unconnected', label: `Без трассы ${unconnected.length}`}]" />
        </div>
        <ul class="px-2 pb-2">
          <li v-for="o in oksShown" :key="o.oks_id">
            <button type="button" class="group flex w-full items-center gap-3 rounded-xl px-2 py-2 text-left hover:bg-surface-2" @click="goOks(o.oks_id)"
              @mouseenter="hoverOks(o.oks_id)" @mouseleave="unhoverOks" @focus="hoverOks(o.oks_id)" @blur="unhoverOks">
              <span class="size-2 shrink-0 rounded-full" :class="o.connected ? 'bg-ok' : 'bg-bad'" aria-hidden="true" />
              <span class="min-w-0 flex-1">
                <span class="flex items-baseline gap-2">
                  <span class="text-[13.5px] font-semibold text-ink">ОКС {{ o.oks_id }}</span>
                  <span v-if="o.connected" class="num ml-auto shrink-0 text-[13px] text-ink">{{ fmt(o.own_cost / 1e6, 1) }} <span class="text-xs text-ink-3">млн ₽</span></span>
                </span>
                <span v-if="o.connected" class="num block truncate text-xs text-ink-3">
                  {{ o.joins === 'tie_in' ? 'своя врезка' : 'разветвление' }} {{ o.tie_object_id }} · {{ fmt(o.flow_tph, 1) }} т/ч · ДУ {{ o.diameter }} · {{ fmt(o.own_length, 0) }} м
                </span>
                <span v-else class="block truncate text-xs text-bad">{{ cap(o.reason_text) || 'Без трассы' }}</span>
              </span>
              <ArrowRight class="size-4 shrink-0 text-ink-4 transition-transform group-hover:translate-x-0.5 group-hover:text-accent" aria-hidden="true" />
            </button>
          </li>
          <li v-if="!oksShown.length" class="px-2 py-3 text-center text-[13px] text-ink-3">Нет ОКС по этому условию</li>
        </ul>
        <div v-if="oksList.length > oksShown.length" class="px-4 pb-3">
          <UiButton block size="sm" variant="ghost" @click="showAllOks = true">Показать ещё {{ oksList.length - oksShown.length }}</UiButton>
        </div>
      </section>

      <!-- existing network -->
      <UiSection v-if="app.variantData" title="Пропускная способность сети" :icon="Wrench" persist="result.recon" :default-open="!!d.recon.length"
        :tone="d.recon.length ? 'bad' : 'ok'" :meta="d.recon.length ? count(d.recon.length, 'участок', 'участка', 'участков') : 'хватает'">
        <p v-if="!d.recon.length" class="text-[13px] text-ok">Пропускной способности существующей сети хватает.</p>
        <template v-else>
          <p class="mb-2 text-[12.5px] text-ink-3">Этим участкам после подключения понадобится больший ДУ. В стоимость варианта не входит: по техническим требованиям существующая сеть не перекладывается.</p>
          <table class="tbl">
            <thead><tr><th>Участок</th><th class="r">Расход, т/ч</th><th class="r">ДУ</th></tr></thead>
            <tbody><tr v-for="f in d.recon" :key="f.properties.existing_object_id" class="click" @click="highlight({type: 'FeatureCollection', features: [f]}, {maxZoom: 18})">
              <td>{{ f.properties.existing_object_id }}</td>
              <td class="r">{{ fmt(f.properties.existing_flow_tph, 1) }} → {{ fmt(f.properties.calculated_flow_tph, 1) }}</td>
              <td class="r">{{ f.properties.existing_diameter }} → <b class="text-bad">{{ f.properties.required_diameter }}</b></td>
            </tr></tbody>
          </table>
        </template>
      </UiSection>

      <UiSection v-if="app.variantData && d.specials.length" title="Спецпроходы" :icon="Route" persist="result.specials" :default-open="false"
        :meta="count(d.specials.length, 'участок', 'участка', 'участков')">
        <p class="mb-2 text-[12.5px] text-ink-3">Где трасса пересекает дорогу, трамвайные пути, газопровод или кабель. Угол пересечения — не меньше заданного в настройках (по правилам — 45°).</p>
        <table class="tbl">
          <thead><tr><th>Участок</th><th class="r">ДУ</th><th class="r">Длина</th><th class="r">млн ₽</th></tr></thead>
          <tbody><tr v-for="f in d.specials" :key="f.properties.id" class="click" @click="showSeg(f.properties.id)">
            <td>{{ f.properties.id.replace(/^v\d+-seg-/, 'участок ').replace(/^v\d+-/, '') }}</td>
            <td class="r">{{ f.properties.diameter }}</td><td class="r">{{ fmt(f.properties.length, 0) }} м</td><td class="r">{{ fmt(f.properties.cost / 1e6, 2) }}</td>
          </tr></tbody>
        </table>
      </UiSection>

      <UiSection v-if="app.variantData && d.runs.length" title="Предельная длина" :icon="Ruler" persist="result.length" :default-open="false"
        :tone="d.runs.some((f: any) => f.properties.usage > 1) ? 'bad' : 'neutral'"
        :meta="d.runs.some((f: any) => f.properties.usage > 1) ? `превышено: ${d.runs.filter((f: any) => f.properties.usage > 1).length}` : 'в пределах'">
        <p class="mb-2 text-[12.5px] text-ink-3">Непрерывные части новой сети одного ДУ и их доля от предельной длины. Все части — слоем «Предельная длина» на карте.</p>
        <div v-for="(f, i) in d.runs.slice().sort((a: any, b: any) => (b.properties.usage || 0) - (a.properties.usage || 0)).slice(0, 8)" :key="i" class="flex items-center gap-3 py-1.5 text-[13px]">
          <span class="w-14 shrink-0 text-ink-2">ДУ {{ f.properties.diameter ?? '—' }}</span>
          <div class="h-1.5 min-w-0 flex-1 overflow-hidden rounded-full bg-surface-3">
            <div class="h-full rounded-full" :class="f.properties.usage > 1 ? 'bg-bad' : f.properties.usage > 0.8 ? 'bg-usage-mid' : 'bg-ok'" :style="{width: Math.min(100, 100 * (f.properties.usage || 0)) + '%'}" />
          </div>
          <span class="num w-28 shrink-0 text-right text-xs text-ink-3">{{ fmt(f.properties.measured_length, 0) }} из {{ fmt(f.properties.max_length, 0) }} м</span>
        </div>
      </UiSection>

      <UiSection title="Подробности" :icon="ListTree" persist="result.details" :default-open="false">
        <dl class="grid grid-cols-[120px_1fr] gap-x-3 gap-y-1.5 text-[13px]">
          <dt class="text-ink-3">Способ</dt><dd>{{ v.approach || v.explanation }}</dd>
          <dt class="text-ink-3">Частей сети</dt><dd>{{ fmt(v.network_parts) }}</dd>
          <dt class="text-ink-3"><UiTerm term="спецпроход" text="Спецпроходы" /></dt><dd>{{ v.special_segments ? count(v.special_segments, 'участок', 'участка', 'участков') : 'нет' }}</dd>
          <dt class="text-ink-3">Поворотов</dt><dd>{{ fmt(v.turns) }} · {{ fmt(v.turns_per_km, 1) }} на км</dd>
          <template v-if="v.entries">
            <dt class="text-ink-3">Ввод в здания</dt>
            <dd>{{ metres(v.inside_building_m) }} внутри зданий{{ v.entries_not_nearest ? ` · не от ближайшей стены: ${v.entries_not_nearest} (+${metres(v.inside_building_m - v.inside_building_min_m)})` : ' · от ближайших стен' }}</dd>
          </template>
        </dl>
      </UiSection>
    </template>

    <template v-if="run && run.status === 'DONE'" #footer>
      <UiTooltip content="Как подключались ОКС — по шагам на карте">
        <UiButton variant="secondary" :icon="Clapperboard" @click="replay()">Построение</UiButton>
      </UiTooltip>
      <span class="flex-1" />
      <UiButton variant="primary" :icon="Download" @click="toExport">Выгрузить</UiButton>
    </template>
  </PanelLayout>
</template>
