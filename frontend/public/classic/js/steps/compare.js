// Result, tab «Сравнение» — two variants side by side: of one calculation or of calculations on different
// parameters and versions of the data. What differs in the input is shown first, so a difference in the result can
// be explained by it.

import {api} from '../core/api.js';
import {h, on, replace} from '../core/dom.js';
import {fmt, metres, when} from '../core/format.js';
import {go} from '../core/router.js';
import {store, subscribe} from '../core/store.js';
import {loadFamilyRuns, loadRuns} from '../core/data.js';
import {callout, card, empty, icon, toast} from '../ui/components.js';
import {addLayer, bboxOf, cssVar, defineGroup, dropGroups, fit, removeLayers, removeSources, setData} from '../map/map.js';

export function create(root) {
  let family = null;
  let familyFor = null;
  let result = null;
  let resultKey = null;
  let loading = false;

  const options = () => {
    const out = [];
    for (const r of family || []) {
      if (r.status !== 'DONE') continue;
      for (let i = 1; i <= (r.variants || 1); i++) {
        out.push({value: `${r.id}:${i}`, label: `${r.name || 'Расчёт'} · Вариант ${i}${r.dataset_version > 1 ? ` · версия ${r.dataset_version}` : ''} · ${when(r.created_at)}`});
      }
    }
    return out;
  };

  /** Without a second variant: another variant of the same calculation; otherwise the newest other calculation. */
  const defaults = () => {
    const r = store.route;
    const opts = options();
    if (!opts.length) return;
    const a = r.runA ? `${r.runA}:${r.variantA || 1}` : opts[0].value;
    let b = r.runB ? `${r.runB}:${r.variantB || 1}` : null;
    if (!b) {
      const [ra] = a.split(':');
      const sameRunOther = opts.find((o) => o.value.startsWith(ra + ':') && o.value !== a);
      const otherRun = opts.find((o) => !o.value.startsWith(ra + ':'));
      b = (sameRunOther || otherRun || opts[0]).value;
    }
    if (!r.runA || !r.runB) {
      const [runA, variantA] = a.split(':');
      const [runB, variantB] = b.split(':');
      go({step: 'compare', runA, variantA, runB, variantB}, {replace: true});
    }
  };

  const load = async () => {
    const r = store.route;
    if (familyFor !== r.dataset) {
      familyFor = r.dataset;
      family = null;
      render();
      family = await loadFamilyRuns(r.dataset);
      defaults();
      render();
    }
    if (!r.runA || !r.runB) return;
    const key = `${r.runA}:${r.variantA}|${r.runB}:${r.variantB}`;
    if (resultKey === key) return;
    resultKey = key;
    result = null;
    loading = true;
    render();
    try {
      const [cmp, fc] = await Promise.all([api.compare(`${r.runA}:${r.variantA}`, `${r.runB}:${r.variantB}`),
        api.compareFeatures(`${r.runA}:${r.variantA}`, `${r.runB}:${r.variantB}`)]);
      if (resultKey !== key) return;
      result = cmp;
      showOnMap(fc);
    } catch (e) {
      toast('Сравнение не удалось: ' + e.message, true);
    } finally {
      loading = false;
      render();
    }
  };

  const showOnMap = (fc) => {
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
      paint: {'circle-radius': 6, 'circle-color': color, 'circle-stroke-color': cssVar('--card'), 'circle-stroke-width': 2}});
    defineGroup('cmp-common', {section: 'overlay', title: 'Общая новая сеть', layers: ['cmp-line', 'cmp-multi', 'cmp-tie'], sw: colC, kind: 'line'});
    defineGroup('cmp-a', {section: 'overlay', title: 'Только в А', layers: [], sw: colA, kind: 'line', display: true});
    defineGroup('cmp-b', {section: 'overlay', title: 'Только в Б', layers: [], sw: colB, kind: 'line', display: true});
    const b = bboxOf(fc.features);
    if (b) fit(b);
  };

  const clearMap = () => {
    removeLayers('cmp-');
    removeSources('cmp');
    dropGroups('cmp');
  };

  const sideCard = (label, side, s) => h('div', {class: 'card tight side-' + side},
    h('div', {class: 'row'}, h('b', null, `${label}: вариант ${s.variant_id}`), h('span', {class: 'badge outline'}, `${s.rank} место`)),
    h('div', {class: 'small'}, `${s.name}${s.dataset_version > 1 ? ` · версия ${s.dataset_version}` : ''} · ${s.strategy === 'separate' ? 'раздельное' : 'совместное'}`),
    h('div', {class: 'small muted'}, s.plain));

  const render = () => {
    const r = store.route;
    if (!r.dataset) return replace(root, empty('Набор не выбран', 'Выберите набор на шаге «Исходные данные».'));
    const opts = options();
    const head = h('div', {class: 'step-head'}, h('div', {class: 'title-row'}, h('h2', null, icon('compare'), 'Сравнение вариантов')));
    if (family == null) return replace(root, head, h('div', {class: 'muted'}, 'Загрузка расчётов…'));
    if (!opts.length) return replace(root, head, empty('Нет завершённых расчётов', 'Для сравнения нужны два варианта.', h('button', {type: 'button', onclick: () => go({step: 'calc'})}, icon('calculator'), 'К расчёту')));
    const a = `${r.runA}:${r.variantA}`;
    const b = `${r.runB}:${r.variantB}`;
    const pickers = card('Варианты', h('div', {class: 'stack'},
      h('label', {class: 'field'}, h('span', {class: 'label'}, h('span', {class: 'dot a'}), ' Вариант А'),
        h('select', {dataset: {pick: 'a'}}, opts.map((o) => h('option', {value: o.value, selected: o.value === a}, o.label)))),
      h('label', {class: 'field'}, h('span', {class: 'label'}, h('span', {class: 'dot b'}), ' Вариант Б'),
        h('select', {dataset: {pick: 'b'}}, opts.map((o) => h('option', {value: o.value, selected: o.value === b}, o.label)))),
      h('div', {class: 'row'}, h('button', {type: 'button', class: 'sm', dataset: {act: 'swap'}}, icon('swap'), 'Поменять местами'))), {icon: 'compare'});
    if (!result) return replace(root, head, pickers, h('div', {class: 'muted'}, loading ? 'Сравнение…' : ''));
    const d = result.data;
    const inputDiff = card('Различия исходных данных', h('div', null,
      d.same_dataset ? h('p', {class: 'small'}, 'Одна версия набора.')
        : d.same_family ? h('div', null, h('p', {class: 'small'}, `Версии ${result.a.dataset_version} и ${result.b.dataset_version} одного набора.`),
          editList('Правки только в А', d.edits_only_a), editList('Правки только в Б', d.edits_only_b))
          : callout('warn', 'Разные наборы данных — сравнение результатов условно.'),
      result.params.length ? h('table', {class: 't compact', style: {marginTop: '6px'}},
        h('thead', null, h('tr', null, h('th', null, 'Параметр'), h('th', null, 'А'), h('th', null, 'Б'))),
        h('tbody', null, result.params.map((p) => h('tr', null, h('td', {class: 'small'}, p.title), h('td', {class: 'small'}, p.a), h('td', {class: 'small'}, p.b)))))
        : h('p', {class: 'small'}, 'Параметры расчёта одинаковы.')), {icon: 'database'});
    const g = result.geometry;
    const metrics = card('Показатели', h('div', null,
      h('p', {class: 'lead-sentence'}, result.explanation),
      h('table', {class: 't compact'},
        h('thead', null, h('tr', null, h('th', null, ''), h('th', {class: 'num'}, 'А'), h('th', {class: 'num'}, 'Б'), h('th', {class: 'num'}, 'Б − А'))),
        h('tbody', null, result.metrics.map((m) => {
          const money = m.unit === 'руб.';
          const f = (x) => (money ? fmt(x / 1e6, 1) : m.key === 'score' ? fmt(x, 2) : m.unit === 'м' ? fmt(x, 0) : fmt(x, Number.isInteger(x) ? 0 : 2));
          const sub = m.title[0] === m.title[0].toLowerCase();
          const cls = (side) => (m.better === side ? 'num better' : m.better && m.better !== 'equal' ? 'num worse' : 'num');
          return h('tr', {class: sub ? 'sub' : ''},
            h('td', {class: 'small'}, (sub ? m.title : m.title) + (money && !sub ? ', млн ₽' : m.unit === 'м' ? ', м' : '')),
            h('td', {class: m.better === 'a' ? 'num better' : 'num'}, f(m.a)),
            h('td', {class: m.better === 'b' ? 'num better' : 'num'}, f(m.b)),
            h('td', {class: cls('b')}, m.delta === 0 ? '—' : (m.delta > 0 ? '+' : '−') + f(Math.abs(m.delta))));
        }))),
      h('p', {class: 'small muted'}, h('span', {class: 'ok-text'}, '■'), ' зелёным — лучше (меньше)')), {icon: 'calculator'});
    const geo = card('Геометрия', h('div', null,
      h('div', {class: 'legend-row'},
        h('span', null, h('i', {class: 'dot common'}), `Общее ${metres(g.common_length)}`),
        h('span', null, h('i', {class: 'dot a'}), `Только А ${metres(g.only_a_length)}`),
        h('span', null, h('i', {class: 'dot b'}), `Только Б ${metres(g.only_b_length)}`)),
      h('p', {class: 'small muted', style: {marginTop: '6px'}}, `Новая сеть двух вариантов на карте; совпадение с точностью ${fmt(g.tolerance_m)} м. Точки — врезки.`),
      h('div', {class: 'row'},
        h('button', {type: 'button', class: 'sm', dataset: {open: 'a'}}, icon('external'), 'Открыть А'),
        h('button', {type: 'button', class: 'sm', dataset: {open: 'b'}}, icon('external'), 'Открыть Б'))), {icon: 'map'});
    replace(root, head, pickers, h('div', {class: 'cols-2', style: {marginBottom: '12px'}}, sideCard('А', 'a', result.a), sideCard('Б', 'b', result.b)),
      inputDiff, metrics, geo);
  };

  const editList = (title, list) => (list.length ? h('div', {class: 'small'}, h('b', null, title + ': '),
    list.map((e) => `${e.title}, ${fmt(e.area_m2)} м²${e.comment ? ' (' + e.comment + ')' : ''}`).join('; ')) : null);

  on(root, 'change', '[data-pick]', (e, t) => {
    const [run, variant] = t.value.split(':');
    go(t.dataset.pick === 'a' ? {runA: run, variantA: variant} : {runB: run, variantB: variant});
  });
  on(root, 'click', '[data-act=swap]', () => {
    const r = store.route;
    go({runA: r.runB, variantA: r.variantB, runB: r.runA, variantB: r.variantA});
  });
  on(root, 'click', '[data-open]', (e, t) => {
    const s = result[t.dataset.open];
    go({step: 'result', dataset: s.dataset_id, run: s.run_id, variant: s.variant_id, oks: null});
  });

  let familyKey = '';
  subscribe('runs', () => {
    if (store.route.step !== 'compare') return;
    const key = (store.runs || []).filter((x) => x.status === 'DONE').map((x) => x.id).join(',');
    if (key === familyKey) return;
    familyKey = key;
    familyFor = null;
  });

  return {
    update() {
      if (store.route.dataset) loadRuns(store.route.dataset);
      load().catch((e) => toast(e.message, true));
      render();
    },
    leave() {
      clearMap();
      resultKey = null;
    },
  };
}
