<script setup lang="ts">
// Step 2 — check of the data: readiness for a calculation, errors and notes (each can be shown on the map), what
// the service completed itself, the contents of the dataset; edits of the input (an added restriction) saved as a
// new version.

import {computed, onBeforeUnmount, reactive, ref, watch} from 'vue';
import {RouterLink, useRouter} from 'vue-router';
import {ArrowRight, Check, CircleAlert, Info, Layers, MapPin, Pencil, Pentagon, Plus, Settings2, ShieldAlert, ShieldCheck, Trash2, LoaderCircle, Undo2} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {api} from '@/api/client';
import {bytes, count, fmt, metres, splitName} from '@/lib/format';
import {toast} from '@/lib/toast';
import {confirmDialog} from '@/lib/dialogs';
import {prefs} from '@/lib/prefs';
import {clearHighlight, highlight} from '@/map/engine';
import {cancelDraw, drawPoints, drawing, finishDraw, showPending, startDraw} from '@/map/draw';
import PanelLayout from '@/components/shell/PanelLayout.vue';
import UiButton from '@/components/ui/UiButton.vue';
import UiBadge from '@/components/ui/UiBadge.vue';
import UiSection from '@/components/ui/UiSection.vue';
import UiExpandRow from '@/components/ui/UiExpandRow.vue';
import UiMark from '@/components/ui/UiMark.vue';
import UiProgress from '@/components/ui/UiProgress.vue';
import UiStat from '@/components/ui/UiStat.vue';
import UiIconButton from '@/components/ui/UiIconButton.vue';
import UiSelect from '@/components/ui/UiSelect.vue';
import UiTerm from '@/components/ui/UiTerm.vue';
import UiTooltip from '@/components/ui/UiTooltip.vue';

const app = useApp();
const router = useRouter();
const o = computed(() => app.currentOverview);
const title = computed(() => (o.value ? splitName(o.value.name).title : 'Набор'));

const TYPES: [string, string][] = [
  ['source', 'Источник'], ['heat_chamber', 'Существующие камеры'], ['oks_future', 'Контуры перспективных ОКС'],
  ['oks_connection_point', 'Точки подключения'], ['oks_existing', 'Существующие здания'],
];

const failed = computed(() => (o.value?.readiness || []).filter((c: any) => !c.ok));
const passed = computed(() => (o.value?.readiness || []).filter((c: any) => c.ok));
const problems = computed(() => (o.value?.issues || []).filter((i: any) => i.group !== 'assumption'));
const errors = computed(() => problems.value.filter((i: any) => i.group === 'problem'));
const assumptions = computed(() => (o.value?.issues || []).filter((i: any) => i.group === 'assumption'));
const netLen = computed(() => (o.value?.contents?.network_by_dn || []).reduce((a: number, x: any) => a + (x.length_m || 0), 0));
const catalogEntry = (key: string) => (app.catalog ? app.catalog.params.find((p: any) => p.key === key) : null);

// ------------------------------------------------------------------ examples of an issue, on the map

const examples = ref<Record<string, any[]> | null>(null);
async function loadExamples() {
  if (examples.value || !app.datasetId) return;
  const all = await api.issues(app.datasetId);
  const by: Record<string, any[]> = {};
  for (const i of all) (by[i.code] ||= []).push(i);
  examples.value = by;
}
async function showIssue(code: string) {
  try {
    const fc: any = await api.issueFeatures(app.datasetId!, code);
    if (!fc.features.length) return toast('Замечание относится ко всему набору');
    highlight(fc);
  } catch (e: any) { toast(e.message, true); }
}
async function showFeature(fid: string) {
  try {
    const fc: any = await api.feature(app.datasetId!, fid);
    if (!fc.features.length) return toast('Объект не найден');
    highlight(fc, {maxZoom: 18});
  } catch (e: any) { toast(e.message, true); }
}

// ------------------------------------------------------------------ edits of the input

const types = computed(() => (app.catalog ? app.catalog.restriction_types : []));
const typeTitle = (t: string) => (types.value.find((x: any) => x.type === t) || {}).title || t;
const pending = ref<any[]>([]);
let pendingFor: string | null = null;
const form = ref<any>(null);
const draft = reactive({note: '', comment: '', type: 'prohibited_site'});
const saving = ref(false);
const editable = computed(() => !!o.value && !!o.value.parent_id && !o.value.run_count);
const rule = computed(() => (types.value.find((t: any) => t.type === draft.type) || {}).rule || '');

const drawPendingOnMap = () => showPending(pending.value.map((p) => p.geometry).concat(form.value ? [form.value] : [])
  .map((geometry: any) => ({type: 'Feature', properties: {}, geometry})));
watch(() => o.value && o.value.id, (id) => {
  if (!id || pendingFor === id) return;
  pendingFor = id;
  pending.value = prefs.get('pending.' + id, []);
  drawPendingOnMap();
}, {immediate: true});
const savePending = () => {
  prefs.set('pending.' + pendingFor, pending.value);
  drawPendingOnMap();
};

function draw() {
  startDraw({
    onFinish: (geometry) => { form.value = geometry; drawPendingOnMap(); },
    onCancel: () => {},
  });
}
function addPending() {
  pending.value.push({op: 'add_restriction', restriction_type: draft.type, comment: draft.comment.trim() || null, geometry: form.value});
  draft.comment = '';
  form.value = null;
  savePending();
}
function dropPending(i: number) {
  pending.value.splice(i, 1);
  savePending();
}
function showEdit(e: any) {
  highlight({type: 'FeatureCollection', features: [{type: 'Feature', properties: {}, geometry: e.geometry}]}, {maxZoom: 18});
}
async function dropEdit(e: any) {
  const ov = o.value;
  const rest = (ov.edits || []).filter((x: any) => x.id !== e.id);
  if (!rest.length) {
    if (!await confirmDialog('Удалить версию?', 'Это последняя правка версии — версия будет удалена целиком.')) return;
    try {
      await api.deleteDataset(ov.id);
      toast('Версия удалена');
      await app.loadDatasets();
      router.push({name: 'check', params: {ds: ov.parent_id}});
    } catch (err: any) { toast(err.message, true); }
    return;
  }
  try {
    await api.updateEdits(ov.id, rest);
    toast('Версия обновляется');
    app.loadOverview(ov.id);
  } catch (err: any) { toast(err.message, true); }
}
async function saveVersion() {
  saving.value = true;
  try {
    const r: any = await api.createVersion(app.datasetId!, pending.value, draft.note);
    pending.value = [];
    savePending();
    draft.note = '';
    toast('Создаётся новая версия набора', {tone: 'ok'});
    await app.loadDatasets();
    router.push({name: 'check', params: {ds: r.id}});
  } catch (err: any) {
    toast('Не удалось сохранить: ' + err.message, true);
  } finally {
    saving.value = false;
  }
}
async function addToVersion() {
  saving.value = true;
  const ov = o.value;
  try {
    await api.updateEdits(ov.id, (ov.edits || []).map((x: any) => ({op: x.op, restriction_type: x.restriction_type, comment: x.comment, geometry: x.geometry})).concat(pending.value));
    pending.value = [];
    savePending();
    toast('Версия обновляется');
    app.loadOverview(ov.id);
  } catch (err: any) {
    toast(err.message, true);
  } finally {
    saving.value = false;
  }
}

onBeforeUnmount(() => {
  cancelDraw();
  clearHighlight();
  showPending([]);
});

const toCalc = () => router.push({name: 'calc', params: {ds: app.datasetId}});
</script>

<template>
  <PanelLayout :title="title" eyebrow="Шаг 2 · Проверка данных" :back="{to: '/', label: 'Все наборы'}">
    <template #title-extra>
      <UiBadge v-if="o && o.version > 1" tone="outline">версия {{ o.version }}</UiBadge>
    </template>
    <template #sub>
      <div v-if="o" class="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[13px] text-ink-3">
        <span>{{ bytes(o.file_bytes) }}</span>
        <span v-if="o.feature_count != null">· {{ count(o.feature_count, 'объект', 'объекта', 'объектов') }}</span>
        <template v-if="o.lineage && o.lineage.length > 1">
          <span>·</span>
          <span class="inline-flex items-center gap-1"><UiTerm term="версия набора" text="Версии:" />
            <template v-for="v in o.lineage" :key="v.id">
              <span v-if="v.id === o.id" class="rounded-full bg-ink px-1.5 text-[11px] font-semibold text-surface-solid">{{ v.version }}</span>
              <RouterLink v-else :to="{name: 'check', params: {ds: v.id}}" :title="v.note || ''" class="rounded-full bg-surface-3 px-1.5 text-[11px] font-semibold text-ink-2 hover:bg-accent-soft hover:text-accent-ink">{{ v.version }}</RouterLink>
            </template>
          </span>
        </template>
      </div>
    </template>

    <!-- loading -->
    <div v-if="!o" class="space-y-3"><div class="skeleton h-28" /><div class="skeleton h-16" /><div class="skeleton h-40" /></div>

    <!-- import in progress -->
    <div v-else-if="o.status !== 'READY'" class="card p-4">
      <div class="flex items-center gap-3">
        <div class="flex size-10 items-center justify-center rounded-2xl" :class="o.status === 'FAILED' ? 'bg-bad-soft text-bad' : 'bg-accent-soft text-accent'">
          <CircleAlert v-if="o.status === 'FAILED' || o.status === 'CANCELLED'" class="size-5" aria-hidden="true" />
          <LoaderCircle v-else class="size-5 animate-spin" aria-hidden="true" />
        </div>
        <div class="min-w-0 flex-1">
          <div class="font-semibold">{{ o.status === 'FAILED' ? 'Импорт не удался' : o.status === 'CANCELLED' ? 'Импорт отменён' : o.parent_id ? 'Собирается версия набора' : 'Идёт импорт набора' }}</div>
          <div class="text-[13px] text-ink-3">{{ o.status === 'FAILED' ? o.error || 'ошибка' : o.status === 'CANCELLED' ? 'Загрузите файл заново.' : 'Можно закрыть страницу — импорт продолжится на сервере.' }}</div>
        </div>
      </div>
      <UiProgress v-if="o.status !== 'FAILED' && o.status !== 'CANCELLED'" class="mt-3" :value="o.progress?.bytes_read" :max="o.progress?.bytes_total"
        :label="o.progress?.bytes_total ? `прочитано ${fmt(100 * o.progress.bytes_read / o.progress.bytes_total)} %` : 'разбор объектов'" />
    </div>

    <template v-else>
      <!-- readiness -->
      <div class="card overflow-hidden">
        <div class="flex items-start gap-3 p-4" :class="app.blocking.length ? 'bg-bad-soft' : 'bg-ok-soft'">
          <div class="flex size-10 shrink-0 items-center justify-center rounded-2xl bg-surface-solid shadow-soft" :class="app.blocking.length ? 'text-bad' : 'text-ok'">
            <component :is="app.blocking.length ? ShieldAlert : ShieldCheck" class="size-5" aria-hidden="true" />
          </div>
          <div class="min-w-0 flex-1">
            <div class="text-[15px] font-semibold" :class="app.blocking.length ? 'text-bad' : 'text-ok'">{{ app.blocking.length ? 'Расчёт невозможен' : 'Набор готов к расчёту' }}</div>
            <p class="text-[13px] text-ink-2">
              {{ app.blocking.length ? 'Есть блокирующие ошибки — их нужно исправить во входном файле.'
                : failed.length || problems.length ? 'Есть замечания — расчёту не мешают, но влияют на результат.' : 'Все проверки пройдены, ошибок нет.' }}
            </p>
          </div>
        </div>
        <div class="px-4 py-1">
          <div v-for="c in failed" :key="c.id" class="flex items-start gap-2.5 border-b border-line py-2.5 last:border-b-0">
            <UiMark :tone="c.blocking ? 'bad' : 'warn'" class="mt-0.5" />
            <div class="min-w-0"><div class="text-[13.5px] font-medium">{{ c.title }}</div><div class="text-xs text-ink-3">{{ c.detail }}</div></div>
          </div>
          <UiExpandRow>
            <template #lead><UiMark tone="ok" /></template>
            <template #title>Пройдено проверок: {{ passed.length }} из {{ o.readiness.length }}</template>
            <div v-for="c in passed" :key="c.id" class="py-1"><span class="text-ink">{{ c.title }}</span> <span class="text-ink-3">— {{ c.detail }}</span></div>
          </UiExpandRow>
        </div>
      </div>

      <!-- figures -->
      <div class="grid grid-cols-3 gap-2">
        <UiStat label="ОКС" :value="fmt(o.contents.oks.count)" :hint="o.contents.oks.count ? `Расход всего ${fmt(o.contents.oks.flow_total, 1)} т/ч (${fmt(o.contents.oks.flow_min, 1)}…${fmt(o.contents.oks.flow_max, 1)})` : undefined" />
        <UiStat label="Сеть" :value="metres(netLen)" :hint="`${fmt(o.contents.types.heat_network || 0)} участков существующей сети`" />
        <UiStat label="Ограничений" :value="fmt(o.contents.types.restriction || 0)" />
      </div>

      <!-- errors and notes -->
      <UiSection title="Ошибки и замечания" :icon="CircleAlert" :tone="errors.length ? 'bad' : problems.length ? 'warn' : 'ok'" persist="check.problems"
        :meta="problems.length ? [errors.length ? `ошибок ${errors.length}` : '', problems.length > errors.length ? `замечаний ${problems.length - errors.length}` : ''].filter(Boolean).join(' · ') : 'нет'">
        <p v-if="!problems.length" class="flex items-center gap-2 text-[13px] text-ok"><Check class="size-4" aria-hidden="true" />Ошибок и замечаний нет.</p>
        <UiExpandRow v-for="i in problems" :key="i.code" @open="loadExamples">
          <template #lead><UiMark :tone="i.group === 'problem' ? 'bad' : 'warn'" /></template>
          <template #title>{{ i.title }}</template>
          <template #meta>{{ i.whole_dataset ? 'весь набор' : fmt(i.count) }}</template>
          <p>{{ i.meaning }}</p>
          <div v-if="!i.whole_dataset" class="mt-2 flex flex-wrap items-center gap-1.5">
            <UiButton size="xs" variant="soft" :icon="MapPin" @click="showIssue(i.code)">Показать на карте</UiButton>
            <UiTooltip v-for="x in (examples?.[i.code] || []).filter((x) => x.feature_id).slice(0, 12)" :key="x.feature_id + x.message" :content="x.message">
              <button type="button" class="h-7 rounded-lg bg-surface-2 px-2 font-mono text-[11.5px] text-ink-2 hover:bg-surface-3 hover:text-ink" @click="showFeature(x.feature_id)">{{ x.feature_id }}</button>
            </UiTooltip>
          </div>
        </UiExpandRow>
      </UiSection>

      <!-- assumptions -->
      <UiSection title="Как прочитаны данные" :icon="Info" persist="check.assumptions" :default-open="false"
        :meta="assumptions.length ? count(assumptions.length, 'пункт', 'пункта', 'пунктов') : 'всё есть'">
        <p v-if="!assumptions.length" class="text-[13px] text-ink-3">Все нужные значения есть во входных данных.</p>
        <template v-else>
          <p class="mb-1 text-[12.5px] text-ink-3">Чего нет во входном файле и как сервис это восполнил. Расчёт опирается на эти значения.</p>
          <UiExpandRow v-for="i in assumptions" :key="i.code">
            <template #lead><Info class="size-4 text-accent" aria-hidden="true" /></template>
            <template #title>{{ i.title }}</template>
            <template #meta>{{ i.whole_dataset ? 'весь набор' : fmt(i.count) }}</template>
            <p>{{ i.meaning }}</p>
            <p v-if="i.param && catalogEntry(i.param)" class="mt-1 text-xs text-ink-3">
              {{ catalogEntry(i.param).editable ? 'Можно изменить в настройках расчёта.' : `Принято: ${catalogEntry(i.param).value}.` }}
            </p>
            <div class="mt-2 flex flex-wrap gap-1.5">
              <UiButton v-if="!i.whole_dataset" size="xs" variant="soft" :icon="MapPin" @click="showIssue(i.code)">На карте</UiButton>
              <UiButton v-if="i.param && catalogEntry(i.param)?.editable" size="xs" :icon="Settings2"
                @click="router.push({name: 'calc', params: {ds: app.datasetId}, query: {param: i.param}})">Изменить в расчёте</UiButton>
            </div>
          </UiExpandRow>
        </template>
      </UiSection>

      <!-- contents -->
      <UiSection title="Состав набора" :icon="Layers" persist="check.contents" :default-open="false" :meta="count(o.feature_count || 0, 'объект', 'объекта', 'объектов')">
        <table class="tbl">
          <tbody>
            <tr>
              <td>Существующая сеть</td><td class="r">{{ fmt(o.contents.types.heat_network || 0) }}</td>
              <td class="r">{{ metres(netLen) }}</td>
            </tr>
            <tr v-if="o.contents.network_by_dn?.length">
              <td colspan="3" class="!pt-0">
                <div class="flex flex-wrap gap-1">
                  <span v-for="x in o.contents.network_by_dn" :key="String(x.dn)" class="rounded-md bg-surface-2 px-1.5 py-0.5 text-[11.5px] text-ink-2">ДУ {{ x.dn == null ? '—' : x.dn }} · {{ metres(x.length_m) }}</span>
                </div>
              </td>
            </tr>
            <tr><td><UiTerm term="ОКС" text="Перспективные ОКС" /></td><td class="r">{{ fmt(o.contents.oks.count) }}</td>
              <td class="r">{{ o.contents.oks.count ? fmt(o.contents.oks.flow_total, 1) + ' т/ч' : '' }}</td></tr>
            <template v-for="[k, t] in TYPES" :key="k">
              <tr v-if="o.contents.types[k] != null"><td>{{ t }}</td><td class="r">{{ fmt(o.contents.types[k]) }}</td><td /></tr>
            </template>
          </tbody>
        </table>
        <template v-if="o.contents.restrictions.length">
          <div class="eyebrow mb-1 mt-4">Ограничения</div>
          <table class="tbl">
            <tbody>
              <tr v-for="x in o.contents.restrictions" :key="x.type">
                <td>
                  <div class="flex flex-wrap items-center gap-1.5">{{ x.title }}
                    <UiBadge v-if="x.edits" tone="warn">вручную: {{ x.edits }}</UiBadge>
                    <UiBadge v-if="!x.known" tone="neutral">вне справочника</UiBadge>
                  </div>
                  <div class="text-xs text-ink-3">{{ x.rule }}</div>
                </td>
                <td class="r">{{ fmt(x.count) }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </UiSection>

      <!-- edits -->
      <UiSection title="Правки входных данных" :icon="Pencil" persist="check.edits" :default-open="!!(o.edits?.length || pending.length)"
        :meta="o.edits?.length ? count(o.edits.length, 'правка', 'правки', 'правок') : pending.length ? `не сохранено: ${pending.length}` : ''" :open="drawing || !!form || undefined">
        <p class="text-[12.5px] text-ink-3">Ограничение, которого нет в данных, можно нарисовать на карте. Исходный файл не меняется: правки сохраняются новой <UiTerm term="версия набора" text="версией набора" />.</p>

        <div v-if="o.edits?.length" class="mt-3">
          <div class="eyebrow mb-1">Правки версии {{ o.version }}</div>
          <div v-for="e in o.edits" :key="e.id" class="flex items-center gap-2 border-b border-line py-2 last:border-b-0">
            <span class="size-2.5 shrink-0 rounded-full bg-edit" aria-hidden="true" />
            <div class="min-w-0 flex-1">
              <div class="text-[13.5px]">{{ typeTitle(e.restriction_type) }}</div>
              <div class="truncate text-xs text-ink-3">{{ fmt(e.area_m2) }} м²{{ e.comment ? ' — ' + e.comment : '' }}</div>
            </div>
            <UiIconButton :icon="MapPin" label="Показать на карте" size="sm" @click="showEdit(e)" />
            <UiIconButton v-if="editable" :icon="Trash2" label="Удалить правку" size="sm" variant="danger" @click="dropEdit(e)" />
          </div>
          <p v-if="o.parent_id && o.run_count" class="mt-1 text-xs text-ink-3">Версия использована в расчётах — новые правки создадут следующую версию.</p>
        </div>

        <!-- drawing -->
        <div v-if="drawing" class="mt-3 rounded-2xl border border-accent bg-accent-soft p-3">
          <div class="flex items-center gap-2 text-[13.5px] font-medium text-accent-ink"><Pentagon class="size-4" aria-hidden="true" />Рисуете контур на карте · вершин: {{ drawPoints }}</div>
          <p class="mt-1 text-xs text-ink-2">Щёлкайте по карте, чтобы поставить вершины. Замкните контур щелчком по первой вершине.</p>
          <div class="mt-2 flex gap-2">
            <UiButton size="sm" variant="primary" :icon="Check" :disabled="drawPoints < 3" @click="finishDraw()">Готово</UiButton>
            <UiButton size="sm" variant="ghost" @click="cancelDraw()">Отмена</UiButton>
          </div>
        </div>

        <!-- describing a drawn contour -->
        <div v-else-if="form" class="mt-3 space-y-3 rounded-2xl border border-line-strong bg-surface-2 p-3">
          <div class="flex items-center gap-2 text-[13.5px] font-semibold"><Pentagon class="size-4 text-edit" aria-hidden="true" />Новое ограничение</div>
          <div>
            <span class="label">Тип</span>
            <UiSelect v-model="draft.type" :options="types.map((t: any) => ({value: t.type, label: t.title}))" label="Тип ограничения" />
            <p v-if="rule" class="mt-1 text-xs text-ink-3">Правило: {{ rule }}</p>
          </div>
          <label class="block"><span class="label">Комментарий</span>
            <input v-model="draft.comment" class="input" placeholder="Что это и откуда известно" /></label>
          <div class="flex gap-2">
            <UiButton size="sm" variant="primary" :icon="Plus" @click="addPending">Добавить</UiButton>
            <UiButton size="sm" variant="ghost" @click="form = null; drawPendingOnMap()">Отмена</UiButton>
          </div>
        </div>

        <!-- pending -->
        <div v-if="pending.length" class="mt-3">
          <div class="eyebrow mb-1">Не сохранено · {{ pending.length }}</div>
          <div v-for="(p, i) in pending" :key="i" class="flex items-center gap-2 border-b border-line py-2 last:border-b-0">
            <span class="size-2.5 shrink-0 rounded-full border-2 border-dashed border-edit" aria-hidden="true" />
            <div class="min-w-0 flex-1">
              <div class="text-[13.5px]">{{ typeTitle(p.restriction_type) }}</div>
              <div v-if="p.comment" class="truncate text-xs text-ink-3">{{ p.comment }}</div>
            </div>
            <UiIconButton :icon="Undo2" label="Убрать" size="sm" variant="danger" @click="dropPending(i)" />
          </div>
          <label class="mt-2 block"><span class="label">Описание версии</span>
            <input v-model="draft.note" class="input" placeholder="Например: стройплощадка до конца года" /></label>
          <div class="mt-2 flex flex-wrap gap-2">
            <UiButton variant="primary" size="sm" :icon="Check" :loading="saving" @click="saveVersion">Сохранить новой версией</UiButton>
            <UiButton v-if="editable" size="sm" :disabled="saving" @click="addToVersion">Добавить в эту версию</UiButton>
          </div>
        </div>

        <UiButton v-if="!drawing && !form" class="mt-3" size="sm" :icon="Pentagon" @click="draw">Нарисовать ограничение</UiButton>
      </UiSection>
    </template>

    <template #footer>
      <div v-if="o && o.status === 'READY'" class="flex w-full items-center gap-3">
        <p class="min-w-0 flex-1 text-[12.5px] leading-snug text-ink-3">
          <template v-if="app.blocking.length"><span class="font-medium text-bad">Нельзя рассчитать:</span> {{ app.blocking.map((b: any) => b.title).join('; ') }}</template>
          <template v-else>Дальше — настройки и запуск расчёта</template>
        </p>
        <UiButton variant="primary" size="lg" :icon-right="ArrowRight" :disabled="app.blocking.length > 0" @click="toCalc">К расчёту</UiButton>
      </div>
      <UiButton v-else block variant="ghost" @click="router.push('/')">К списку наборов</UiButton>
    </template>
  </PanelLayout>
</template>
