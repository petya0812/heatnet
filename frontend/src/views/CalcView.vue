<script setup lang="ts">
// Step 3 — calculation. One clear order from top to bottom: what can be changed (settings of the routing, values
// missing in the data, how rules are applied), then the name, then — folded — the rules that never change. The
// start button is always in the same place at the bottom. History of calculations is the second tab.

import {computed, nextTick, onMounted, reactive, ref, watch} from 'vue';
import {useRoute, useRouter} from 'vue-router';
import {ArrowRight, CircleAlert, Clapperboard, Ellipsis, History, Lock, Pencil, Pin, PinOff, Play, RotateCcw, Route,
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
import UiProgress from '@/components/ui/UiProgress.vue';
import UiEmpty from '@/components/ui/UiEmpty.vue';
import UiMenu from '@/components/ui/UiMenu.vue';
import UiIconButton from '@/components/ui/UiIconButton.vue';
import ParamField from '@/components/calc/ParamField.vue';

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
/** A group is a list of rows; a setting that depends on another one of the same group sits inside the row of that one. */
const groups = computed(() => GROUPS.map((g) => {
  const items = editable.value.filter((e) => e.class === g.cls);
  const keys = new Set(items.map((e) => e.key));
  const rows = items.filter((e) => !(e.depends_on && keys.has(e.depends_on.key)))
    .map((e) => ({e, children: items.filter((c) => c.depends_on && c.depends_on.key === e.key)}));
  return {...g, items, rows};
}).filter((g) => g.items.length));

/** A dependent setting whose parent is in another group says when it applies: «Действует, когда «Ввод в здание» — …». */
function conditionOf(e: any) {
  const d = e.depends_on;
  if (!d) return undefined;
  const parent = editable.value.find((x) => x.key === d.key);
  const label = (v: unknown) => (parent ? paramLabel(parent, v) : String(v));
  const name = parent ? parent.title : d.key;
  return d.not != null ? `Действует, когда «${name}» — не «${label(d.not)}»` : `Действует, когда «${name}» — «${label(d.value)}»`;
}
const fixed = computed(() => {
  const by: Record<string, any[]> = {};
  for (const e of entries.value.filter((x) => !x.editable)) (by[e.class] ||= []).push(e);
  return ['norm', 'interpretation', 'data_assumption', 'algorithm'].filter((c) => by[c]).map((c) => ({cls: c, title: FIXED_TITLES[c], items: by[c]}));
});
const fixedCount = computed(() => entries.value.filter((x) => !x.editable).length);

/** Folded groups (a per-viewer convenience); a folded group shows its current values in one line. */
const folded = reactive<Record<string, boolean>>(prefs.get('calcFolded', {}));
watch(folded, () => prefs.set('calcFolded', {...folded}));
const summaryOf = (items: any[]) => items.filter((e) => paramVisible(e, values.value)).slice(0, 3)
  .map((e) => `${e.title}: ${paramLabel(e, values.value[e.key])}`).join(' · ');
/** Folding from the header that stays on top while scrolling: the folded group comes back into view. */
async function toggle(cls: string, ev: Event) {
  folded[cls] = !folded[cls];
  const section = (ev.currentTarget as HTMLElement).closest('section');
  await nextTick();
  // the scroll of the panel body only: scrollIntoView would also move the panel itself inside the page
  const body = section?.closest('.overflow-y-auto');
  if (section && body) {
    const above = section.getBoundingClientRect().top - body.getBoundingClientRect().top;
    if (above < 0) body.scrollTop += above - 8;
  }
}

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

      <!-- editable groups: one frame per group; its header stays on top while the group is scrolled -->
      <section v-for="g in groups" :key="g.cls" class="card overflow-clip" :aria-labelledby="'g-' + g.cls">
        <button type="button" class="sticky -top-1 z-[2] flex w-full items-start gap-2.5 bg-surface-solid px-4 pb-2.5 pt-3.5 text-left transition-colors hover:bg-surface-2"
          :class="folded[g.cls] ? '' : 'shadow-[0_1px_0_var(--line)]'" :aria-expanded="!folded[g.cls]" @click="toggle(g.cls, $event)">
          <component :is="g.icon" class="mt-0.5 size-[18px] shrink-0 text-accent" aria-hidden="true" />
          <span class="min-w-0 flex-1">
            <h3 :id="'g-' + g.cls" class="text-[14px] font-semibold">{{ g.title }}</h3>
            <span class="block text-xs text-ink-3">{{ folded[g.cls] ? summaryOf(g.items) : g.desc }}</span>
          </span>
          <span v-if="g.items.filter(changed).length" class="mt-0.5 flex items-center gap-1 text-xs font-medium text-accent-ink"><span class="size-1.5 rounded-full bg-accent" aria-hidden="true" />изменено {{ g.items.filter(changed).length }}</span>
          <ChevronDown class="mt-0.5 size-4 shrink-0 text-ink-4 transition-transform" :class="folded[g.cls] ? '' : 'rotate-180'" aria-hidden="true" />
        </button>
        <div v-show="!folded[g.cls]" class="divide-y divide-line">
          <template v-for="row in g.rows" :key="row.e.key">
            <div v-if="paramVisible(row.e, values)" :id="'param-' + row.e.key" class="px-4 py-3.5 transition-colors duration-500" :class="flash === row.e.key ? 'bg-accent-soft' : ''">
              <ParamField :e="row.e" :condition="conditionOf(row.e)">
                <template v-if="row.children.some((c) => paramVisible(c, values))" #default>
                  <template v-for="c in row.children" :key="c.key">
                    <div v-if="paramVisible(c, values)" :id="'param-' + c.key" class="-mx-1.5 rounded-lg px-1.5 py-1 transition-colors duration-500" :class="flash === c.key ? 'bg-surface-solid' : ''">
                      <ParamField :e="c" />
                    </div>
                  </template>
                </template>
              </ParamField>
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
