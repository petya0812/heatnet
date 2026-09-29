// Step 2 — check of the data: readiness for a calculation, errors and notes, what the service completed itself
// (assumptions), the contents of the dataset; edits of the input (an added restriction) saved as a new version.

import {api} from '../core/api.js';
import {h, keepFocus, on, replace} from '../core/dom.js';
import {bytes, count, fmt, metres} from '../core/format.js';
import {go} from '../core/router.js';
import {prefs, store, subscribe} from '../core/store.js';
import {loadDatasets, loadOverview} from '../core/data.js';
import {callout, card, classBadge, confirmDialog, empty, icon, iconButton, mark, progress, status, toast} from '../ui/components.js';
import {term} from '../ui/glossary.js';
import {clearHighlight, closePopups, highlight} from '../map/map.js';
import {cancelDraw, showPending, startDraw} from '../map/draw.js';
import {splitName} from './data.js';

const TYPES = [
  ['source', 'Источник'],
  ['heat_chamber', 'Существующие камеры'],
  ['oks_future', 'Контуры перспективных ОКС'],
  ['oks_connection_point', 'Точки подключения'],
  ['oks_existing', 'Существующие здания'],
];

export function create(root) {
  let pending = [];
  let pendingFor = null;
  let form = null; // geometry being described
  let examples = {}; // code → issues
  // Значения полей живут здесь, а не в DOM: иначе перерисовка панели стирает то, что человек печатает.
  const draft = {note: '', comment: '', type: ''};

  const loadPending = (id) => {
    if (pendingFor === id) return;
    pendingFor = id;
    pending = prefs.get('pending.' + id, []);
    showPending(pending.map((p) => ({type: 'Feature', properties: {}, geometry: p.geometry})));
  };
  /** Unsaved edits and the contour being described are both drawn on the map. */
  const drawPending = () => showPending(pending.map((p) => p.geometry).concat(form ? [form] : [])
    .map((geometry) => ({type: 'Feature', properties: {}, geometry})));
  const savePending = () => {
    prefs.set('pending.' + pendingFor, pending);
    drawPending();
  };

  const catalogEntry = (key) => (store.catalog ? store.catalog.params.find((p) => p.key === key) : null);

  const render = () => {
    const r = store.route;
    const o = store.overview;
    if (!r.dataset) return replace(root, empty('Набор не выбран', 'Выберите набор на шаге «Исходные данные».'));
    if (!o || o.id !== r.dataset) return replace(root, h('div', {class: 'muted'}, 'Загрузка…'));
    loadPending(o.id);
    const head = h('div', {class: 'step-head'},
      h('div', {class: 'title-row'}, h('h2', null, icon('shield-check'), 'Проверка данных'), h('span', {class: 'spacer'}), status(o.status)),
      h('div', {class: 'sub'}, h('b', null, splitName(o.name).title), h('span', {class: 'muted'}, bytes(o.file_bytes)),
        o.feature_count != null ? h('span', {class: 'muted'}, '· ' + count(o.feature_count, 'объект', 'объекта', 'объектов')) : null),
      lineage(o));
    if (o.status !== 'READY') {
      const p = o.progress;
      return replace(root, head,
        card(o.parent_id ? 'Сборка версии' : 'Импорт набора', h('div', null,
          o.status === 'FAILED' ? callout('bad', 'Не удалось: ' + (o.error || 'ошибка')) :
            o.status === 'CANCELLED' ? callout('warn', 'Импорт отменён.') :
              [p && p.bytes_total ? progress(p.bytes_read, p.bytes_total, `прочитано ${fmt(100 * p.bytes_read / p.bytes_total, 0)} %`) : progress(0, 0, 'разбор объектов'),
                h('p', {class: 'small muted'}, 'Можно закрыть страницу — импорт продолжится на сервере.')]), {icon: 'loader'}));
    }
    const blocking = o.readiness.filter((c) => c.blocking);
    replace(root, head,
      h('div', {class: 'actions-row'},
        h('button', {type: 'button', class: 'primary', disabled: blocking.length > 0, dataset: {act: 'calc'}}, 'К расчёту', icon('arrow-right')),
        blocking.length ? h('span', {class: 'small bad-text'}, 'Расчёт недоступен: ' + blocking.map((b) => b.title).join('; ')) : null),
      readiness(o, blocking),
      problems(o),
      assumptions(o),
      contents(o),
      edits(o));
    const cont = root.querySelector('#contents-card');
    if (cont && cont.tagName === 'DETAILS') cont.addEventListener('toggle', () => prefs.set('checkContents', cont.open));
  };

  const lineage = (o) => {
    if (!o.lineage || o.lineage.length < 2) return null;
    return h('div', {class: 'chips', style: {marginTop: '4px'}}, h('span', {class: 'small muted'}, term('версия набора', 'Версии:')),
      o.lineage.map((v, i) => [i ? h('span', {class: 'muted'}, icon('chevron-right', 'sm')) : null,
        v.id === o.id ? h('span', {class: 'chip on'}, `${v.version}`) : h('a', {class: 'chip', href: `#/datasets/${v.id}`, title: v.note || ''}, `${v.version}`)]));
  };

  const readiness = (o, blocking) => {
    const bad = o.readiness.filter((c) => !c.ok);
    const row = (c) => h('div', {class: 'item tight'}, mark(c.ok ? 'ok' : c.blocking ? 'bad' : 'warn'),
      h('div', {class: 'grow'}, h('div', {class: 'title'}, c.title), h('div', {class: 'small muted'}, c.detail)));
    return card('Готовность к расчёту', h('div', null,
      blocking.length ? callout('bad', 'Расчёт невозможен: есть блокирующие ошибки.')
        : callout('ok', 'Набор готов к расчёту.' + (bad.length ? ' Есть замечания — расчёту не мешают, но влияют на результат.' : '')),
      bad.map(row),
      h('details', {class: 'ref', dataset: {key: 'readiness'}},
        h('summary', null, h('span', {class: 't'}, 'Проверки'),
          h('span', {class: 'v'}, `${o.readiness.length - bad.length} из ${o.readiness.length} пройдено`)),
        h('div', {class: 'body'}, o.readiness.filter((c) => c.ok).map(row)))),
    {icon: 'check-circle'});
  };

  const contents = (o) => {
    const c = o.contents;
    const t = c.types || {};
    const net = c.network_by_dn || [];
    const netLen = net.reduce((a, x) => a + (x.length_m || 0), 0);
    const rows = [
      h('tr', null, h('td', null, 'Существующая сеть'), h('td', {class: 'num'}, count(t.heat_network || 0, 'участок', 'участка', 'участков')),
        h('td', null, metres(netLen), h('div', {class: 'chips', style: {marginTop: '3px'}}, net.map((x) => h('span', {class: 'chip'}, [term('ДУ', 'ДУ'), ` ${x.dn == null ? 'не задан' : x.dn} — ${metres(x.length_m)}`]))))),
      h('tr', null, h('td', null, term('ОКС', 'Перспективные ОКС')), h('td', {class: 'num'}, fmt(c.oks.count)),
        h('td', null, c.oks.count ? [term('расход', 'Расход'), ` ${fmt(c.oks.flow_total, 1)} т/ч (${fmt(c.oks.flow_min, 1)}…${fmt(c.oks.flow_max, 1)})`] : 'нет')),
    ];
    for (const [k, title] of TYPES) {
      if (t[k] != null) rows.push(h('tr', null, h('td', null, title), h('td', {class: 'num'}, fmt(t[k])), h('td', null)));
    }
    const restr = c.restrictions.map((x) => h('tr', null,
      h('td', null, x.title, x.edits ? h('span', {class: 'badge warn', style: {marginLeft: '4px'}}, `добавлено вручную: ${x.edits}`) : null),
      h('td', {class: 'num'}, fmt(x.count)),
      h('td', {class: x.known ? '' : 'muted'}, x.known ? x.rule : h('span', null, h('span', {class: 'badge data_assumption'}, 'неизвестный тип'), ' ', x.rule))));
    return card('Состав набора', h('div', null,
      h('table', {class: 't'}, h('thead', null, h('tr', null, h('th', null, 'Объекты'), h('th', {class: 'num'}, 'Число'), h('th', null, 'Подробности'))), h('tbody', null, rows)),
      restr.length ? [h('h3', {style: {margin: '12px 0 4px'}}, 'Ограничения'),
        h('table', {class: 't'}, h('thead', null, h('tr', null, h('th', null, 'Тип'), h('th', {class: 'num'}, 'Объектов'), h('th', null, 'Правило учёта'))), h('tbody', null, restr))] : null),
    {collapsible: true, open: prefs.get('checkContents', true), id: 'contents-card', icon: 'layers',
      count: count(o.feature_count || 0, 'объект', 'объекта', 'объектов')});
  };

  const assumptions = (o) => {
    const list = o.issues.filter((i) => i.group === 'assumption');
    const body = list.length ? list.map((i) => {
      const e = i.param ? catalogEntry(i.param) : null;
      return h('details', {class: 'ref', dataset: {key: i.code}},
        h('summary', null,
          h('span', {class: 't'}, i.title),
          h('span', {class: 'v'}, i.whole_dataset ? 'весь набор' : count(i.count, 'объект', 'объекта', 'объектов')),
          e ? classBadge(e.class) : null),
        h('div', {class: 'body'},
          h('p', null, i.meaning),
          e ? h('p', {class: 'src'}, e.editable ? 'Изменяется в параметрах расчёта.' : `Принято: ${e.value}.`) : null,
          h('div', {class: 'row'},
            i.whole_dataset ? null : h('button', {type: 'button', class: 'sm', dataset: {show: i.code}}, icon('map-pin'), 'На карте'),
            e && e.editable ? h('button', {type: 'button', class: 'sm', dataset: {param: e.key}}, icon('settings'), 'Изменить') : null)));
    }) : callout('ok', 'Все нужные значения есть во входных данных.');
    return card(['Как прочитаны данные'], h('div', null,
      list.length ? h('p', {class: 'small muted'}, 'Чего нет во входном файле и как сервис это восполнил. Расчёт опирается на эти значения.') : null, body),
    {count: list.length ? count(list.length, 'пункт', 'пункта', 'пунктов') : '', icon: 'info'});
  };

  const problems = (o) => {
    const list = o.issues.filter((i) => i.group !== 'assumption');
    if (!list.length) return card('Ошибки и замечания', callout('ok', 'Ошибок и замечаний нет.'), {icon: 'alert-circle'});
    const errors = list.filter((i) => i.group === 'problem');
    return card('Ошибки и замечания', h('div', null,
      list.map((i) => h('details', {class: 'ref', dataset: {examples: i.code, key: i.code}},
        h('summary', null,
          mark(i.group === 'problem' ? 'bad' : 'warn'),
          h('span', {class: 't'}, i.title),
          h('span', {class: 'v'}, i.whole_dataset ? 'весь набор' : fmt(i.count))),
        h('div', {class: 'body'},
          h('p', null, i.meaning),
          h('div', {class: 'row'},
            i.whole_dataset ? null : h('button', {type: 'button', class: 'sm', dataset: {show: i.code}}, icon('map-pin'), 'На карте')),
          h('div', {class: 'chips', style: {marginTop: '4px'}}, (examples[i.code] || []).slice(0, 24).map((x) => x.feature_id
            ? h('button', {type: 'button', class: 'chip', dataset: {fid: x.feature_id}, title: x.message}, x.feature_id) : null)))))),
    {count: `${errors.length ? 'ошибок: ' + errors.length : ''}${errors.length && list.length > errors.length ? ' · ' : ''}${list.length > errors.length ? 'замечаний: ' + (list.length - errors.length) : ''}`,
      icon: 'alert-circle'});
  };

  const edits = (o) => {
    const types = store.catalog ? store.catalog.restriction_types : [];
    const own = o.edits || [];
    const editable = o.parent_id && !o.run_count;
    const title = (t) => (types.find((x) => x.type === t) || {}).title || t;
    return card('Правки входных данных', h('div', null,
      h('p', {class: 'small muted'}, 'Ограничение, которого нет в данных, можно нарисовать на карте. Исходный файл не меняется: правки сохраняются новой ', term('версия набора', 'версией набора'), '.'),
      own.length ? h('div', null, h('h3', {style: {margin: '8px 0 4px'}}, `Правки версии ${o.version}`),
        own.map((e) => h('div', {class: 'item'},
          h('span', {class: 'dot edit', style: {marginTop: '6px'}}),
          h('div', {class: 'grow'}, h('div', {class: 'title'}, title(e.restriction_type)), h('div', {class: 'small muted'}, `${fmt(e.area_m2)} м²${e.comment ? ' — ' + e.comment : ''}`)),
          h('div', {class: 'actions'}, iconButton('map-pin', 'На карте', {class: 'sm', dataset: {showEdit: e.id}}),
            editable ? iconButton('trash', 'Удалить правку', {class: 'sm danger', dataset: {dropEdit: e.id}}) : null))),
        o.parent_id && o.run_count ? h('p', {class: 'small muted'}, 'Версия использована в расчётах — новые правки создадут следующую версию.') : null) : null,
      form ? editForm(types) : null,
      pending.length ? h('div', null, h('h3', {style: {margin: '8px 0 4px'}}, `Не сохранено: ${pending.length}`),
        pending.map((p, i) => h('div', {class: 'item'},
          h('span', {class: 'dot edit-pending', style: {marginTop: '6px'}}),
          h('div', {class: 'grow'}, h('div', {class: 'title'}, title(p.restriction_type)), p.comment ? h('div', {class: 'small muted'}, p.comment) : null),
          h('div', {class: 'actions'}, iconButton('trash', 'Убрать', {class: 'sm danger', dataset: {dropPending: i}})))),
        h('label', {class: 'field', style: {marginTop: '8px'}}, h('span', {class: 'label'}, 'Описание версии'),
          h('input', {type: 'text', dataset: {act: 'note'}, placeholder: 'Например: стройплощадка, действует до конца года',
            value: draft.note, oninput: (ev) => { draft.note = ev.target.value; }})),
        h('div', {class: 'row', style: {marginTop: '8px'}},
          h('button', {type: 'button', class: 'primary', dataset: {act: 'save-version'}}, icon('check'), 'Сохранить новой версией'),
          editable ? h('button', {type: 'button', dataset: {act: 'add-to-version'}}, 'Добавить в эту версию') : null)) : null,
      !form ? h('div', {class: 'row', style: {marginTop: '8px'}},
        h('button', {type: 'button', dataset: {act: 'draw'}}, icon('pentagon'), 'Нарисовать ограничение')) : null),
    {icon: 'pencil', count: own.length ? count(own.length, 'правка', 'правки', 'правок') : ''});
  };

  const editForm = (types) => {
    const sel = draft.type || 'prohibited_site';
    const rule = (types.find((t) => t.type === sel) || {}).rule || '';
    return h('div', {class: 'card tight edit'},
      h('h3', null, icon('pentagon'), 'Новое ограничение'),
      h('label', {class: 'field'}, h('span', {class: 'label'}, 'Тип ограничения'),
        h('select', {dataset: {act: 'type'}, style: {width: '100%'}, onchange: (ev) => { draft.type = ev.target.value; render(); }},
          types.map((t) => h('option', {value: t.type, selected: t.type === sel}, t.title)))),
      rule ? h('div', {class: 'small muted', style: {margin: '4px 0 8px'}}, 'Правило: ' + rule) : null,
      h('label', {class: 'field'}, h('span', {class: 'label'}, 'Комментарий'),
        h('input', {type: 'text', dataset: {act: 'comment'}, placeholder: 'Что это и откуда известно',
          value: draft.comment, oninput: (ev) => { draft.comment = ev.target.value; }})),
      h('div', {class: 'row', style: {marginTop: '8px'}},
        h('button', {type: 'button', class: 'primary', dataset: {act: 'add-pending'}}, icon('plus'), 'Добавить'),
        h('button', {type: 'button', dataset: {act: 'cancel-form'}}, 'Отмена')));
  };

  // ---------------------------------------------------------------- actions

  on(root, 'click', '[data-act=calc]', () => go({step: 'calc'}));
  on(root, 'click', '[data-param]', (e, t) => go({step: 'calc', query: {param: t.dataset.param}}));
  on(root, 'click', '[data-show]', async (e, t) => {
    try {
      const fc = await api.issueFeatures(store.route.dataset, t.dataset.show);
      if (!fc.features.length) return toast('Замечание относится ко всему набору');
      closePopups();
      highlight(fc);
    } catch (err) { toast(err.message, true); }
  });
  on(root, 'click', '[data-fid]', async (e, t) => {
    try {
      const fc = await api.feature(store.route.dataset, t.dataset.fid);
      if (!fc.features.length) return toast('Объект не найден');
      highlight(fc);
    } catch (err) { toast(err.message, true); }
  });
  root.addEventListener('toggle', async (e) => {
    const d = e.target;
    if (!d.open || !d.dataset || !d.dataset.examples || examples[d.dataset.examples]) return;
    const all = await api.issues(store.route.dataset);
    examples = {};
    for (const i of all) (examples[i.code] = examples[i.code] || []).push(i);
    keepOpen(() => render());
  }, true);

  /** Перерисовка с сохранением того, что человек раскрыл. */
  const keepOpen = (fn) => {
    const open = [...root.querySelectorAll('details[open]')].map((d) => d.dataset.examples || d.dataset.open || '');
    fn();
    for (const d of root.querySelectorAll('details')) {
      const key = d.dataset.examples || d.dataset.open || '';
      if (key && open.includes(key)) d.open = true;
    }
  };
  on(root, 'click', '[data-act=draw]', () => {
    startDraw({
      onFinish: (geometry) => { form = geometry; drawPending(); render(); },
      onCancel: () => render(),
    });
  });
  on(root, 'click', '[data-act=cancel-form]', () => { form = null; drawPending(); render(); });
  on(root, 'click', '[data-act=add-pending]', () => {
    const sel = root.querySelector('[data-act=type]');
    pending.push({op: 'add_restriction', restriction_type: draft.type || (sel && sel.value),
      comment: draft.comment.trim() || null, geometry: form});
    draft.comment = '';
    form = null;
    savePending();
    render();
  });
  on(root, 'click', '[data-drop-pending]', (e, t) => {
    pending.splice(Number(t.dataset.dropPending), 1);
    savePending();
    render();
  });
  on(root, 'click', '[data-show-edit]', (e, t) => {
    const ed = (store.overview.edits || []).find((x) => x.id === t.dataset.showEdit);
    if (ed) highlight({type: 'FeatureCollection', features: [{type: 'Feature', properties: {}, geometry: ed.geometry}]}, {maxZoom: 18});
  });
  on(root, 'click', '[data-drop-edit]', async (e, t) => {
    const o = store.overview;
    const rest = (o.edits || []).filter((x) => x.id !== t.dataset.dropEdit);
    if (!rest.length) {
      if (!await confirmDialog('Удалить версию?', 'Это последняя правка версии — версия будет удалена целиком.')) return;
      try {
        await api.deleteDataset(o.id);
        toast('Версия удалена');
        await loadDatasets();
        go({step: 'check', dataset: o.parent_id});
      } catch (err) { toast(err.message, true); }
      return;
    }
    try {
      await api.updateEdits(o.id, rest);
      toast('Версия обновляется');
      loadOverview(o.id);
    } catch (err) { toast(err.message, true); }
  });
  on(root, 'click', '[data-act=save-version]', async (e, t) => {
    t.disabled = true;
    const note = draft.note;
    try {
      const r = await api.createVersion(store.route.dataset, pending, note);
      pending = [];
      savePending();
      toast('Создаётся новая версия набора');
      await loadDatasets();
      go({step: 'check', dataset: r.id});
    } catch (err) {
      t.disabled = false;
      toast('Не удалось сохранить: ' + err.message, true);
    }
  });
  on(root, 'click', '[data-act=add-to-version]', async (e, t) => {
    t.disabled = true;
    const o = store.overview;
    try {
      await api.updateEdits(o.id, (o.edits || []).map((x) => ({op: x.op, restriction_type: x.restriction_type, comment: x.comment, geometry: x.geometry})).concat(pending));
      pending = [];
      savePending();
      toast('Версия обновляется');
      loadOverview(o.id);
    } catch (err) {
      t.disabled = false;
      toast(err.message, true);
    }
  });

  subscribe('overview', () => store.route.step === 'check' && keepFocus(root, render));
  subscribe('catalog', () => store.route.step === 'check' && keepFocus(root, render));

  return {
    update() {
      render();
    },
    leave() {
      cancelDraw();
      clearHighlight();
      showPending([]);
      pendingFor = null;
      form = null;
    },
  };
}
