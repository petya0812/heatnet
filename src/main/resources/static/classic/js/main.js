// Entry point: steps in the panel, the address as the place in the workflow, the map synchronized with the route.

import {api} from './core/api.js';
import {h, replace} from './core/dom.js';
import {fmt} from './core/format.js';
import {ALL_VIEWS, RESULT_TABS, STEPS, go, start as startRouter, stepOf} from './core/router.js';
import {store, subscribe} from './core/store.js';
import {loadCatalog, loadDatasets, loadOverview, loadRun, loadRuns, loadVariant} from './core/data.js';
import {dialog, icon, tabs, toast} from './ui/components.js';
import {helpContent, installTooltips} from './ui/glossary.js';
import {renderStepper, stepStates} from './ui/stepper.js';
import * as mapview from './map/map.js';
import {installLegend, setLegendRestrictions} from './map/legend.js';
import * as player from './anim/player.js';
import {anim, installMenu} from './anim/settings.js';
import {pauseFlow, syncFlow} from './anim/flow.js';
import * as dataStep from './steps/data.js';
import {splitName} from './steps/data.js';
import * as checkStep from './steps/check.js';
import * as calcStep from './steps/calc.js';
import * as resultStep from './steps/result.js';
import * as compareStep from './steps/compare.js';
import * as exportStep from './steps/export.js';

const panel = document.getElementById('panel');
const factories = {data: dataStep, check: checkStep, calc: calcStep, result: resultStep, compare: compareStep, export: exportStep};
const views = {};
const resultTabs = h('div', {class: 'result-tabs', hidden: true});
for (const id of ALL_VIEWS) {
  if (id === 'result') panel.appendChild(resultTabs);
  const root = h('div', {class: 'step-view', dataset: {step: id}, hidden: true});
  panel.appendChild(root);
  views[id] = factories[id].create(root);
  views[id].root = root;
}

let currentStep = null;
let currentDataset = null;

// ---------------------------------------------------------------- route → data, view, map

async function onRoute(r) {
  // the dataset of the route
  if (r.dataset !== currentDataset) {
    currentDataset = r.dataset;
    store.set('overview', null);
    store.set('runs', []);
    store.set('run', null);
    store.set('variantData', null);
    if (player.isOpen()) player.close(false);
    mapview.clearVariant();
    store.variantKey = null;
    mapview.clearHighlight();
    mapview.closePopups();
    mapview.showDataset(null);
    if (r.dataset) {
      loadOverview(r.dataset).catch((e) => toast(e.message, true));
      loadRuns(r.dataset).catch(() => {});
    }
  }
  // a step without a run of its own takes the latest finished one
  if ((r.step === 'result' || r.step === 'export') && !r.run && r.dataset) {
    const runs = store.runs.length ? store.runs : await loadRuns(r.dataset);
    const done = (runs || []).find((x) => x.status === 'DONE');
    if (done) return go({run: done.id, variant: '1'}, {replace: true});
  }
  if (r.run && (!store.run || store.run.id !== r.run)) {
    loadRun(r.run).catch((e) => toast(e.message, true));
  }
  // view
  if (currentStep !== r.step) {
    if (currentStep && views[currentStep].leave) views[currentStep].leave();
    for (const [id, v] of Object.entries(views)) v.root.hidden = id !== r.step;
    // what belongs to the previous page does not follow to the next one: popups, highlight, replay of a build
    mapview.closePopups();
    mapview.clearHighlight();
    if (player.isOpen() && !(r.step === 'result' && currentStep === 'calc') && !(r.step === 'calc' && currentStep === 'result')) {
      player.close(false);
    }
    currentStep = r.step;
    panel.scrollTop = 0;
  }
  views[r.step].update(r);
  syncVariant();
  renderChrome();
}

/** The variant on the map: on the result and download steps. */
async function syncVariant() {
  const r = store.route;
  const want = (r.step === 'result' || r.step === 'export') && store.run && store.run.id === r.run && store.run.status === 'DONE';
  if (!want) {
    if (r.step !== 'result' && r.step !== 'export') {
      mapview.clearVariant();
      store.variantKey = null;
    }
    return;
  }
  const v = r.variant || '1';
  const key = r.run + '|' + v;
  if (store.variantKey === key) return;
  store.variantKey = key;
  try {
    const data = await loadVariant(r.run, v);
    if (store.variantKey !== key) return;
    const had = mapview.hasVariant();
    await mapview.showVariant(key, data, {transitionMs: had && anim.transitions ? anim.transitionMs : 0});
    store.set('variantData', data);
    store.set('variantShown', key);
    if (!had) {
      const b = mapview.bboxOf(data.features.filter((f) => f.properties.object_type !== 'variant_summary'));
      if (b) mapview.fit(b);
    }
  } catch (e) {
    toast('Не удалось загрузить вариант: ' + e.message, true);
  }
}

// ---------------------------------------------------------------- top bar, context line, result tabs

function renderChrome() {
  renderStepper(document.getElementById('stepper'), pickStep);
  const r = store.route;
  const o = store.overview;
  const ctx = [];
  const sep = () => h('span', {class: 'sep'}, icon('chevron-right'));
  ctx.push(h('a', {class: 'ctx' + (!r.dataset ? ' current' : ''), href: '#/'}, icon('database'), 'Наборы'));
  if (r.dataset) {
    const name = o && o.id === r.dataset ? splitName(o.name).title : 'набор';
    const version = o && o.id === r.dataset && o.version > 1 ? h('span', {class: 'badge outline'}, 'версия ' + o.version) : null;
    ctx.push(sep(), h('a', {class: 'ctx' + (r.step === 'check' || r.step === 'calc' ? ' current' : ''), href: `#/datasets/${r.dataset}`, title: 'К проверке данных'},
      icon('file'), h('b', null, name), version));
  }
  if (r.run && store.run && store.run.id === r.run) {
    ctx.push(sep(), h('a', {class: 'ctx', href: `#/datasets/${r.dataset}/runs/${r.run}/variants/${r.variant || 1}`, title: 'К результату'},
      icon('calculator'), h('b', null, store.run.name || 'расчёт')));
    ctx.push(sep(), h('span', {class: 'ctx' + (r.step === 'result' && !r.oks ? ' current' : '')}, icon('map'), `Вариант ${r.variant || 1}`));
    if (r.oks) ctx.push(sep(), h('span', {class: 'ctx current'}, icon('map-pin'), `ОКС ${r.oks}`));
    if (r.step === 'export') ctx.push(sep(), h('span', {class: 'ctx current'}, icon('download'), 'Выгрузка'));
  }
  if (r.step === 'compare') ctx.push(sep(), h('span', {class: 'ctx current'}, icon('compare'), 'Сравнение'));
  replace(document.getElementById('context'), ctx);

  // tabs of the result step
  const inResult = stepOf(r.step) === 'result';
  resultTabs.hidden = !inResult;
  if (inResult) {
    const done = (store.runs || []).some((x) => x.status === 'DONE');
    replace(resultTabs, tabs(RESULT_TABS.map((t) => Object.assign({}, t, {
      disabled: t.id === 'compare' ? !done : t.id === 'export' ? !(r.run || done) : false,
      tip: t.id === 'compare' && !done ? 'Нужен хотя бы один завершённый расчёт' : '',
    })), r.step, pickTab));
  }
  const step = STEPS.find((s) => s.id === stepOf(r.step));
  const tab = RESULT_TABS.find((t) => t.id === r.step);
  document.title = `${step ? step.title + (tab && tab.id !== 'result' ? ' · ' + tab.title : '') + ' — ' : ''}Трассы подключения к тепловой сети`;
}

function pickTab(id) {
  const r = store.route;
  const done = (store.runs || []).find((x) => x.status === 'DONE');
  switch (id) {
    case 'result': return go({step: 'result', run: r.run || r.runA || (done && done.id), variant: r.variant || r.variantA || '1', oks: null});
    case 'export': return go({step: 'export', run: r.run || r.runA || (done && done.id), variant: r.variant || r.variantA || '1', oks: null});
    case 'compare': return go({step: 'compare', runA: r.run || (done && done.id), variantA: r.variant || '1', runB: null, variantB: null});
    default:
  }
}

function pickStep(id) {
  const r = store.route;
  const st = stepStates()[id];
  if (!st.enabled && stepOf(r.step) !== id) return;
  const done = (store.runs || []).find((x) => x.status === 'DONE');
  switch (id) {
    case 'data': return go({step: 'data'});
    case 'check': return go({step: 'check', run: null, oks: null});
    case 'calc': return go({step: 'calc', run: null, oks: null});
    case 'result': return go({step: 'result', run: r.run || (done && done.id), variant: r.variant || '1', oks: null});
    default:
  }
}

async function loadStatus() {
  const el = document.getElementById('load');
  try {
    const s = await api.status();
    const busy = s.running + s.queued;
    el.hidden = !busy;
    el.className = 'load';
    replace(el, icon('loader', 'spin'), `Выполняется расчётов: ${fmt(s.running)}${s.queued ? `, в очереди: ${fmt(s.queued)}` : ''}`);
  } catch (e) {
    el.hidden = false;
    el.className = 'load bad';
    replace(el, icon('alert-circle'), 'Нет связи с сервером');
  }
  setTimeout(loadStatus, 4000);
}

// ---------------------------------------------------------------- start

subscribe('route', (r) => onRoute(r).catch((e) => console.error(e)));
for (const key of ['overview', 'runs', 'run', 'datasets']) subscribe(key, renderChrome);
subscribe('run', () => syncVariant());
// the layers of a dataset are shown once it is ready (tiles of a dataset being imported do not exist yet)
subscribe('overview', (o) => {
  if (o && o.id === store.route.dataset && o.status === 'READY') mapview.showDataset(o.id, {fitTo: !mapview.hasVariant(), stamp: o.finished_at || ''});
});
subscribe('anim', () => { syncFlow(); });

mapview.onPopupAction('oks', (oks) => {
  const r = store.route;
  if (r.run) go({step: 'result', oks});
  else toast('Откройте результат расчёта, чтобы посмотреть ОКС');
});

installTooltips();
// the map follows the size of its area (a resized window, an emulated viewport)
window.addEventListener('resize', () => mapview.map.resize());
installLegend();
player.installPlayer();
installMenu(document.getElementById('anim-button'), document.getElementById('anim-menu'));
document.getElementById('help-button').addEventListener('click', () => dialog('Справка', helpContent()));

const observer = new MutationObserver(() => pauseFlow(player.isOpen()));
observer.observe(document.getElementById('player'), {attributes: true, attributeFilter: ['hidden']});

loadCatalog().then((c) => {
  const titles = {};
  for (const t of c.restriction_types) titles[t.type] = t.title;
  titles.railway = 'железная дорога';
  mapview.setRestrictionTitles(Object.assign({oks: 'существующее здание'}, titles));
  setLegendRestrictions(titles);
}).catch((e) => toast('Не удалось загрузить параметры расчёта: ' + e.message, true));
loadDatasets().catch(() => {});
loadStatus();
startRouter();

// hooks for end-to-end checks: read-only state and navigation (go); not used by the page itself
window.heatnet = {
  map: mapview.map,
  store,
  go,
  player: player.snapshot,
  fmt,
  snapshot: () => ({route: store.route, step: currentStep, dataset: store.overview && store.overview.id, run: store.run && store.run.id,
    variantShown: store.variantShown || null, player: player.snapshot(), title: document.title}),
};
