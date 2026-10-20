<script setup lang="ts">
// Step 3 — calculation. One clear order from top to bottom: what can be changed (settings of the routing, values
// missing in the data, how rules are applied), then the name, then — folded — the rules that never change. The
// start button is always in the same place at the bottom. History of calculations is the second tab.

import {computed, nextTick, onMounted, reactive, ref, watch} from 'vue';
import {useRoute, useRouter} from 'vue-router';
import {ArrowRight, BookOpen, CircleAlert, Clapperboard, Ellipsis, History, Info, Lock, Pencil, Pin, PinOff, Play, RotateCcw, Route, ShieldCheck,
  SlidersHorizontal, StickyNote, Trash2, X, LoaderCircle, Check, Database, CircleCheck, Building2, ChevronDown} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {api} from '@/api/client';
import {ago, count, fmt, isActive, splitName, STATUS_RU} from '@/lib/format';
import {paramLabel, paramVisible} from '@/lib/catalog';
import {toast} from '@/lib/toast';
import {confirmDialog, promptDialog} from '@/lib/dialogs';
import {prefs} from '@/lib/prefs';
import {live, watchRun} from '@/lib/live';
import {draftFor, draftValues} from '@/lib/calcDraft';
import PanelLayout from '@/components/shell/PanelLayout.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiSection from '@/components/ui/UiSection.vue';
import UiSegmented from '@/components/ui/UiSegmented.vue';
import UiSlider from '@/components/ui/UiSlider.vue';
import UiProgress from '@/components/ui/UiProgress.vue';
import UiEmpty from '@/components/ui/UiEmpty.vue';
import UiMenu from '@/components/ui/UiMenu.vue';
import UiIconButton from '@/components/ui/UiIconButton.vue';
import UiTooltip from '@/components/ui/UiTooltip.vue';
import UiChoice from '@/components/ui/UiChoice.vue';

const app = useApp();
const route = useRoute();
const router = useRouter();

/** Groups of the editable settings, in this order; the rest of the catalogue is read-only. */
const GROUPS = [
  {cls: 'algorithm', title: 'Трассировка', icon: Route, desc: 'Отступы, углы, совместное подключение, повороты.'},
  {cls: 'interpretation', title: 'Здания: ввод и проход', icon: Building2, desc: 'С какой стены трасса входит в здание и может ли идти сквозь здания.'},
  {cls: 'data_assumption', title: 'Недостающие данные', icon: Database, desc: 'Значений нет во входном файле — сервис восполняет их по правилу.'},
];
const FIXED_TITLES: Record<string, string> = {
  norm: 'Технические требования', interpretation: 'Решения сервиса', data_assumption: 'Восполнение данных', algorithm: 'Поиск трассы',
};
/** Keys with a slider next to the number: the range is the range of the catalogue. */
const SLIDERS = new Set(['turn_penalty', 'odd_joint_turns', 'shape_weight', 'extra_clearance_m', 'min_crossing_angle_deg', 'existing_flow_share', 'entry_slack_m', 'entry_leg_m']);

const tab = computed({
  get: () => (route.query.tab === 'history' ? 'history' : 'new'),
  set: (v: string) => router.replace({query: v === 'history' ? {tab: 'history'} : {}}),
});

const o = computed(() => app.currentOverview);
const title = computed(() => (o.value ? splitName(o.value.name).title : 'Набор'));
const entries = computed<any[]>(() => (app.catalog ? app.catalog.params : []));
const editable = computed(() => entries.value.filter((e) => e.editable));
const defaults = () => Object.fromEntries(editable.value.map((e) => [e.key, e.default]));

// The draft lives while this dataset is open: on another dataset and in a new session — the defaults, so the next
// person does not calculate with someone else's settings without noticing.
const values = draftValues;
watch([() => app.datasetId, () => app.catalog], () => {
  if (!app.catalog || draftFor.value === app.datasetId) return;
  draftFor.value = app.datasetId;
  values.value = defaults();
}, {immediate: true});

// «Изменить параметры и пересчитать»: the settings of an earlier run come into the form (?from=run)
watch([() => route.query.from as string | undefined, () => app.catalog], async ([from]) => {
  if (!from || !app.catalog) return;
  try {
    const r: any = await api.run(from);
    const next = defaults();
    for (const e of editable.value) if (r.params && r.params[e.key] != null) next[e.key] = r.params[e.key];
    values.value = next;
    draftFor.value = app.datasetId;
    toast(`Параметры взяты из расчёта «${r.name || 'Расчёт'}» — измените нужное и запустите`, {tone: 'ok'});
  } catch (e: any) {
    toast(e.message, true);
  }
  router.replace({query: route.query.param ? {param: route.query.param} : {}});
}, {immediate: true});

const changed = (e: any) => e.editable && String(values.value[e.key]) !== String(e.default);
const nChanged = computed(() => editable.value.filter(changed).length);
const groups = computed(() => GROUPS.map((g) => ({...g, items: editable.value.filter((e) => e.class === g.cls)})).filter((g) => g.items.length));
const fixed = computed(() => {
  const by: Record<string, any[]> = {};
  for (const e of entries.value.filter((x) => !x.editable)) (by[e.class] ||= []).push(e);
  return ['norm', 'interpretation', 'data_assumption', 'algorithm'].filter((c) => by[c]).map((c) => ({cls: c, title: FIXED_TITLES[c], items: by[c]}));
});
const fixedCount = computed(() => entries.value.filter((x) => !x.editable).length);

const optionsOf = (e: any) => (e.options || []).map((x: any) => ({value: x.value, label: x.label, hint: x.effect}));
/** Folded groups (a per-viewer convenience); a folded group shows its current values in one line. */
const folded = reactive<Record<string, boolean>>(prefs.get('calcFolded', {}));
watch(folded, () => prefs.set('calcFolded', {...folded}));
const summaryOf = (items: any[]) => items.filter((e) => paramVisible(e, values.value)).slice(0, 3)
  .map((e) => `${e.title}: ${paramLabel(e, values.value[e.key])}`).join(' · ');

/** Options with their own effect read better as cards: the effect of each is seen before choosing. */
const asCards = (e: any) => (e.options || []).length > 2 || (e.options || []).some((x: any) => x.label.length > 18);
const effectOf = (e: any) => ((e.options || []).find((x: any) => String(values.value[e.key]) === x.value) || {}).effect;
const setOption = (e: any, raw: string) => { values.value[e.key] = e.type === 'boolean' ? raw === 'true' : raw; };
const isPct = (e: any) => e.unit === 'доля';
const numRange = (e: any) => (isPct(e) ? {min: 0, max: 100, step: 5} : {min: e.min, max: e.max, step: e.step});
const shown = (e: any) => (isPct(e) ? Math.round(Number(values.value[e.key]) * 100) : Number(values.value[e.key]));
function setNumber(e: any, raw: unknown) {
  const s = String(raw).replace(',', '.').trim();
  if (s === '' || Number.isNaN(Number(s))) return; // an empty field does not turn into 0
  let v = Number(s);
  const r = numRange(e);
  v = Math.min(r.max, Math.max(r.min, v));
  values.value[e.key] = isPct(e) ? v / 100 : v;
}
const reset = (e: any) => { values.value[e.key] = e.default; };
const resetAll = () => { values.value = defaults(); };

// ------------------------------------------------------------------ name, start

const draft = reactive({name: prefs.get('calcName', ''), note: prefs.get('calcNote', '')});
watch(draft, () => { prefs.set('calcName', draft.name); prefs.set('calcNote', draft.note); });
const starting = ref(false);

async function start() {
  if (!app.datasetId) return;
  starting.value = true;
  const body: Record<string, unknown> = {};
  for (const e of editable.value) body[e.key] = values.value[e.key];
  if (draft.name.trim()) body.name = draft.name.trim();
  if (draft.note.trim()) body.note = draft.note.trim();
  try {
    const r: any = await api.startRun(app.datasetId, body);
    draft.name = '';
    draft.note = '';
    toast('Расчёт поставлен в очередь');
    const ds = app.datasetId;
    watchRun(r.id, ds, r.name || 'Расчёт', () => { app.loadRuns(ds); app.loadDatasets(); });
    await app.loadRuns(ds);
  } catch (err: any) {
    toast('Не удалось запустить: ' + err.message, true);
  } finally {
    starting.value = false;
  }
}

// ------------------------------------------------------------------ history

const runs = computed(() => (app.runsFor === app.datasetId ? app.runs : []));
const running = computed(() => runs.value.find((x) => isActive(x.status)));
const finished = computed(() => (live.value && live.value.done === 'DONE' && live.value.dataset === app.datasetId ? runs.value.find((x) => x.id === live.value!.run) : null));
const showAll = ref(false);
const shownRuns = computed(() => runs.value.slice(0, showAll.value ? runs.value.length : 12));

// a calculation started elsewhere (another tab, a colleague) is followed too
watch(running, (r) => {
  if (r && (!live.value || live.value.run !== r.id)) watchRun(r.id, app.datasetId!, r.name || 'Расчёт', () => { app.loadRuns(app.datasetId!); app.loadDatasets(); });
}, {immediate: true});

const openResult = (id: string) => router.push({name: 'result', params: {ds: app.datasetId, run: id, v: '1'}});
const reload = () => app.datasetId && app.loadRuns(app.datasetId);
async function patch(r: any, p: Record<string, unknown>) {
  try { await api.updateRun(r.id, p); reload(); } catch (e: any) { toast(e.message, true); }
}
async function rename(r: any) {
  const name = await promptDialog('Название расчёта', {value: r.name || ''});
  if (name != null) patch(r, {name});
}
async function note(r: any) {
  const n = await promptDialog('Заметка к расчёту', {value: r.note || '', multiline: true, placeholder: 'Цель расчёта, что проверяем'});
  if (n != null) patch(r, {note: n});
}
async function remove(r: any) {
  if (!await confirmDialog('Удалить расчёт?', `«${r.name || 'Расчёт'}» будет удалён вместе с результатом.`)) return;
  try { await api.deleteRun(r.id); toast('Расчёт удалён'); reload(); } catch (e: any) { toast(e.message, true); }
}
async function cancelRun(r: any) {
  try { await api.cancelRun(r.id); reload(); } catch (e: any) { toast(e.message, true); }
}
const showLive = (r: any) => { if (live.value) live.value.closed = false; watchRun(r.id, app.datasetId!, r.name || 'Расчёт', reload); };
const menuOf = (r: any) => [
  ...(isActive(r.status) ? [{label: 'Показать построение', icon: Clapperboard, onSelect: () => showLive(r)}, {label: 'Отменить расчёт', icon: X, onSelect: () => cancelRun(r)}] : []),
  ...(r.status === 'DONE' ? [{label: 'Изменить параметры и пересчитать', icon: SlidersHorizontal, onSelect: () => router.push({query: {from: r.id}})}] : []),
  {label: 'Переименовать', icon: Pencil, onSelect: () => rename(r)},
  {label: r.note ? 'Изменить заметку' : 'Добавить заметку', icon: StickyNote, onSelect: () => note(r)},
  ...(!isActive(r.status) ? [{label: r.pinned ? 'Открепить' : 'Закрепить', icon: r.pinned ? PinOff : Pin, onSelect: () => patch(r, {pinned: !r.pinned})}] : []),
  ...(!isActive(r.status) ? [{separator: true, label: ''}, {label: 'Удалить', icon: Trash2, danger: true, onSelect: () => remove(r)}] : []),
];

onMounted(() => { if (app.datasetId) app.loadRuns(app.datasetId); });

// ------------------------------------------------------------------ a parameter asked for from another place (?param=key)

const focusKey = computed(() => route.query.param as string | undefined);
const flash = ref<string | null>(null);
watch([focusKey, () => app.catalog], async ([k]) => {
  if (!k || !app.catalog) return;
  await nextTick();
  const el = document.getElementById('param-' + k);
  if (el) {
    el.scrollIntoView({block: 'center', behavior: 'smooth'});
    flash.value = k;
    setTimeout(() => { flash.value = null; }, 1600);
  }
}, {immediate: true});
const fixedOpen = computed(() => !!focusKey.value && entries.value.some((e) => e.key === focusKey.value && !e.editable));
</script>

<template>
  <PanelLayout :title="title" eyebrow="Шаг 3 · Расчёт" :back="{to: {name: 'check', params: {ds: app.datasetId}}, label: 'К проверке данных'}">
    <template #title-extra>
      <UiBadge v-if="o && o.version > 1" tone="outline">версия {{ o.version }}</UiBadge>
    </template>
    <template #header>
      <UiSegmented v-model="tab" label="Раздел расчёта" block
        :options="[{value: 'new', label: 'Новый расчёт', icon: SlidersHorizontal}, {value: 'history', label: runs.length ? `История · ${runs.length}` : 'История', icon: History}]" />
    </template>

    <div v-if="!o || !app.catalog" class="space-y-3"><div class="skeleton h-24" /><div class="skeleton h-64" /></div>

    <!-- ============================================================ new calculation -->
    <template v-else-if="tab === 'new'">
      <div v-if="!app.ready" class="flex items-start gap-2.5 rounded-2xl bg-bad-soft p-3 text-[13px] text-bad">
        <CircleAlert class="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        <span>Набор не готов к расчёту. <RouterLink class="font-semibold underline" :to="{name: 'check', params: {ds: app.datasetId}}">Открыть проверку данных</RouterLink></span>
      </div>

      <!-- running now -->
      <div v-if="running" class="card overflow-hidden border-accent/40">
        <div class="flex items-center gap-3 p-3.5">
          <div class="flex size-9 shrink-0 items-center justify-center rounded-xl bg-accent-soft text-accent"><LoaderCircle class="size-[18px] animate-spin" aria-hidden="true" /></div>
          <div class="min-w-0 flex-1">
            <div class="truncate text-[14px] font-semibold">{{ running.name || 'Расчёт' }}</div>
            <div class="text-xs text-ink-3">{{ running.status === 'QUEUED' ? 'В очереди' : running.progress?.stage || 'Считается' }}</div>
          </div>
          <UiIconButton :icon="Clapperboard" label="Показать построение на карте" size="sm" @click="showLive(running)" />
          <UiIconButton :icon="X" label="Отменить расчёт" size="sm" variant="danger" @click="cancelRun(running)" />
        </div>
        <UiProgress class="px-3.5 pb-3" :value="running.progress?.done" :max="running.progress?.total || undefined"
          :label="running.progress?.total ? `${fmt(running.progress.done)} из ${fmt(running.progress.total)}` : undefined" />
      </div>

      <!-- just finished -->
      <div v-else-if="finished" class="card flex items-center gap-3 border-[color-mix(in_srgb,var(--ok),transparent_60%)] bg-ok-soft p-3.5">
        <CircleCheck class="size-5 shrink-0 text-ok" aria-hidden="true" />
        <div class="min-w-0 flex-1"><div class="truncate text-[14px] font-semibold">Расчёт «{{ finished.name }}» готов</div>
          <div v-if="finished.best_score != null" class="text-xs text-ink-2">Лучший вариант: S = {{ fmt(finished.best_score, 2) }}</div></div>
        <UiButton size="sm" variant="primary" :icon-right="ArrowRight" @click="openResult(finished.id)">Открыть</UiButton>
      </div>

      <!-- summary of the settings -->
      <div class="flex items-center gap-2 px-1 pt-1">
        <h2 class="flex-1 text-[15px] font-semibold">Настройки</h2>
        <template v-if="nChanged">
          <UiBadge tone="accent">изменено {{ nChanged }}</UiBadge>
          <UiButton size="xs" variant="ghost" :icon="RotateCcw" @click="resetAll">Сбросить всё</UiButton>
        </template>
        <UiBadge v-else tone="neutral">по умолчанию</UiBadge>
      </div>
      <p class="-mt-1 flex items-start gap-1.5 px-1 text-[12.5px] text-ink-3">
        <ShieldCheck class="mt-px size-3.5 shrink-0 text-ok" aria-hidden="true" />
        Любые значения только ужесточают правила — результат всегда проходит проверку по техническим требованиям.
      </p>

      <!-- editable groups -->
      <section v-for="g in groups" :key="g.cls" class="card" :aria-labelledby="'g-' + g.cls">
        <button type="button" class="flex w-full items-start gap-2.5 rounded-[16px] px-4 pb-2.5 pt-3.5 text-left hover:bg-surface-2" :aria-expanded="!folded[g.cls]"
          @click="folded[g.cls] = !folded[g.cls]">
          <component :is="g.icon" class="mt-0.5 size-[18px] shrink-0 text-accent" aria-hidden="true" />
          <span class="min-w-0 flex-1">
            <h3 :id="'g-' + g.cls" class="text-[14px] font-semibold">{{ g.title }}</h3>
            <span class="block text-xs text-ink-3">{{ folded[g.cls] ? summaryOf(g.items) : g.desc }}</span>
          </span>
          <span v-if="g.items.filter(changed).length" class="mt-0.5 flex items-center gap-1 text-xs font-medium text-accent-ink"><span class="size-1.5 rounded-full bg-accent" aria-hidden="true" />изменено {{ g.items.filter(changed).length }}</span>
          <ChevronDown class="mt-0.5 size-4 shrink-0 text-ink-4 transition-transform" :class="folded[g.cls] ? '' : 'rotate-180'" aria-hidden="true" />
        </button>
        <div v-show="!folded[g.cls]" class="px-2 pb-2">
          <template v-for="e in g.items" :key="e.key">
            <div v-if="paramVisible(e, values)" :id="'param-' + e.key"
              class="relative rounded-xl px-2 py-2.5 transition-colors duration-500"
              :class="[flash === e.key ? 'bg-accent-soft' : '', changed(e) ? 'bg-[color-mix(in_srgb,var(--accent-soft),transparent_45%)]' : '', e.depends_on ? 'ml-4 border-l-2 border-line-strong !rounded-l-none pl-3' : '']">
              <div class="flex items-start gap-2">
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-1.5 text-[13.5px] font-medium text-ink">
                    {{ e.title }}
                    <UiTooltip :content="e.description + (e.source ? ` · ${e.source}` : '')">
                      <button type="button" class="text-ink-4 hover:text-ink-2" :aria-label="'Что это: ' + e.title"><Info class="size-3.5" aria-hidden="true" /></button>
                    </UiTooltip>
                  </div>
                </div>
                <UiTooltip v-if="changed(e)" content="Вернуть значение по умолчанию">
                  <button type="button" class="-my-1 inline-flex h-6 items-center gap-1 rounded-md px-1.5 text-xs font-medium text-accent-ink hover:bg-accent-soft" @click="reset(e)">
                    <RotateCcw class="size-3" aria-hidden="true" />Сбросить
                  </button>
                </UiTooltip>
              </div>
              <div class="mt-2">
                <UiChoice v-if="(e.type === 'enum' || e.type === 'boolean') && asCards(e)" :model-value="String(values[e.key])" :options="optionsOf(e)" :label="e.title"
                  :cols="1" @update:model-value="setOption(e, $event)" />
                <UiSegmented v-else-if="e.type === 'enum' || e.type === 'boolean'" :model-value="String(values[e.key])" :options="optionsOf(e)" :label="e.title" size="sm"
                  @update:model-value="setOption(e, $event)" />
                <div v-else-if="e.type === 'number'" class="flex items-center gap-3">
                  <UiSlider v-if="SLIDERS.has(e.key)" class="flex-1" :model-value="shown(e)" v-bind="numRange(e)" :label="e.title" @update:model-value="setNumber(e, $event)" />
                  <label class="flex h-8 shrink-0 items-center overflow-hidden rounded-[10px] border border-line-strong bg-surface-solid focus-within:border-accent focus-within:shadow-[0_0_0_3px_var(--accent-soft)]">
                    <input type="number" class="num h-full w-16 bg-transparent px-2 text-right text-[13px] outline-none" :value="shown(e)" v-bind="numRange(e)" :aria-label="e.title"
                      @change="setNumber(e, ($event.target as HTMLInputElement).value); ($event.target as HTMLInputElement).value = String(shown(e))" />
                    <span class="pr-2 text-xs text-ink-3">{{ isPct(e) ? '%' : e.unit }}</span>
                  </label>
                </div>
              </div>
              <p v-if="effectOf(e) && !asCards(e)" class="mt-1.5 text-xs leading-snug text-ink-2">{{ effectOf(e) }}</p>
              <p v-if="e.source" class="mt-1 text-[11px] text-ink-3">{{ e.source }}</p>
            </div>
          </template>
        </div>
      </section>

      <!-- name -->
      <UiSection title="Название и заметка" :icon="Pencil" :default-open="!!(draft.name || draft.note)" meta="необязательно">
        <div class="space-y-2">
          <input v-model="draft.name" class="input" aria-label="Название расчёта" placeholder="По умолчанию — из изменённых параметров или дата" />
          <textarea v-model="draft.note" class="input" rows="2" aria-label="Заметка к расчёту" placeholder="Цель расчёта, что проверяем" />
        </div>
      </UiSection>

      <!-- rules that never change -->
      <UiSection title="Неизменяемые правила" :icon="Lock" :default-open="false" :open="fixedOpen || undefined" :meta="`${fixedCount} · только чтение`" persist="calc.fixed">
        <p class="mb-2 text-[12.5px] text-ink-3">Отступы, пересечения, врезки, диаметры, стоимость — то, по чему строится и проверяется любая сеть. Источник — пункт документа.</p>
        <div v-for="f in fixed" :key="f.cls" class="mb-3 last:mb-0">
          <div class="eyebrow mb-1">{{ f.title }}</div>
          <div v-for="e in f.items" :id="'param-' + e.key" :key="e.key" class="flex gap-3 border-b border-line py-2 last:border-b-0"
            :class="flash === e.key ? 'bg-accent-soft' : ''">
            <div class="min-w-0 flex-1">
              <div class="text-[13px] font-medium text-ink">{{ e.title }}</div>
              <div class="text-xs text-ink-3">{{ e.value }}</div>
              <div v-if="e.source" class="text-[11px] text-ink-3">{{ e.source }}</div>
            </div>
          </div>
        </div>
      </UiSection>
    </template>

    <!-- ============================================================ history -->
    <template v-else>
      <UiEmpty v-if="!runs.length" :icon="History" title="Расчётов пока нет" text="Запустите расчёт — результат сохранится в истории.">
        <UiButton variant="primary" :icon="Play" @click="tab = 'new'">Новый расчёт</UiButton>
      </UiEmpty>
      <ul v-else class="space-y-2" aria-label="История расчётов">
        <li v-for="r in shownRuns" :key="r.id">
          <article class="card group relative p-3.5 transition-[box-shadow,border-color]"
            :class="r.status === 'DONE' ? 'cursor-pointer hover:border-line-strong hover:shadow-[0_6px_18px_-8px_rgb(16_24_40/0.22)]' : ''"
            @click="r.status === 'DONE' && openResult(r.id)">
            <div class="flex items-start gap-3">
              <span class="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-xl"
                :class="r.status === 'DONE' ? (r.validation_errors ? 'bg-warn-soft text-warn' : 'bg-ok-soft text-ok') : isActive(r.status) ? 'bg-accent-soft text-accent' : 'bg-bad-soft text-bad'">
                <LoaderCircle v-if="isActive(r.status)" class="size-4 animate-spin" aria-hidden="true" />
                <Check v-else-if="r.status === 'DONE'" class="size-4" stroke-width="2.6" aria-hidden="true" />
                <X v-else class="size-4" aria-hidden="true" />
              </span>
              <div class="min-w-0 flex-1">
                <div class="flex items-center gap-1.5">
                  <a v-if="r.status === 'DONE'" :href="`#/datasets/${app.datasetId}/runs/${r.id}/variants/1`" class="truncate text-[14px] font-semibold text-ink after:absolute after:inset-0" @click.prevent>{{ r.name || 'Расчёт' }}</a>
                  <span v-else class="truncate text-[14px] font-semibold">{{ r.name || 'Расчёт' }}</span>
                  <Pin v-if="r.pinned" class="size-3.5 shrink-0 text-ink-3" aria-label="Закреплён" />
                </div>
                <div class="mt-0.5 flex flex-wrap gap-x-2 text-xs text-ink-3">
                  <span>{{ ago(r.created_at) }}</span>
                  <span v-if="r.status !== 'DONE'">· {{ STATUS_RU[r.status] || r.status }}</span>
                  <span v-if="r.best_score != null">· S = <b class="font-semibold text-ink-2">{{ fmt(r.best_score, 2) }}</b></span>
                  <span v-if="r.validation_errors != null" :class="r.validation_errors ? 'text-warn' : ''">· {{ r.validation_errors ? `нарушений: ${r.validation_errors}` : 'нарушений нет' }}</span>
                </div>
                <p v-if="r.note" class="mt-1 text-[12.5px] italic text-ink-2">{{ r.note }}</p>
                <p v-if="r.error" class="mt-1 text-[12.5px] text-bad">{{ r.error }}</p>
                <UiProgress v-if="isActive(r.status)" class="mt-2" :value="r.progress?.done" :max="r.progress?.total || undefined"
                  :label="r.progress ? `${r.progress.stage}${r.progress.total ? ` · ${fmt(r.progress.done)} из ${fmt(r.progress.total)}` : ''}` : 'в очереди'" />
              </div>
              <div class="relative z-10" @click.stop>
                <UiMenu :items="menuOf(r)"><UiIconButton :icon="Ellipsis" label="Действия с расчётом" size="sm" /></UiMenu>
              </div>
            </div>
          </article>
        </li>
      </ul>
      <UiButton v-if="runs.length > shownRuns.length" block variant="ghost" size="sm" @click="showAll = true">Показать ещё {{ runs.length - shownRuns.length }}</UiButton>
    </template>

    <template #footer>
      <template v-if="tab === 'new'">
        <div class="min-w-0 flex-1 text-[12.5px] leading-snug text-ink-3">
          <div class="font-medium text-ink-2">{{ nChanged ? `Изменено ${nChanged} из ${editable.length}` : 'Параметры по умолчанию' }}
            <button v-if="nChanged" type="button" class="ml-1 font-medium text-accent-ink hover:underline" @click="resetAll">Сбросить</button></div>
          <div>все {{ fmt(o?.contents?.oks?.count || 0) }} ОКС набора</div>
        </div>
        <UiButton variant="primary" size="lg" :icon="Play" :loading="starting" :disabled="!app.ready" @click="start">{{ running ? 'Рассчитать ещё' : 'Рассчитать' }}</UiButton>
      </template>
      <template v-else>
        <span class="flex-1 text-[12.5px] text-ink-3">{{ runs.length ? count(runs.length, 'расчёт', 'расчёта', 'расчётов') : '' }}</span>
        <UiButton v-if="app.doneRuns.length" variant="primary" :icon-right="ArrowRight" @click="openResult(app.doneRuns[0].id)">Последний результат</UiButton>
      </template>
    </template>
  </PanelLayout>
</template>
