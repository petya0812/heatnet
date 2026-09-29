// Step 3 — calculation: parameters grouped by where they come from (settings of the routing, values missing in the
// data, rules of the technical requirements, how the rules are applied), name and note, start; progress with the
// construction shown on the map while it runs; history of calculations (pin, rename, note, cancel, delete).

import {api} from '../core/api.js';
import {h, keepFocus, on, replace} from '../core/dom.js';
import {ACTIVE, count, fmt, when} from '../core/format.js';
import {go} from '../core/router.js';
import {prefs, store, subscribe} from '../core/store.js';
import {loadDatasets, loadRuns} from '../core/data.js';
import {callout, card, confirmDialog, empty, icon, iconButton, paramVisible, progress, promptDialog, status, toast} from '../ui/components.js';
import * as player from '../anim/player.js';
import {anim} from '../anim/settings.js';
import {splitName} from './data.js';

/** Groups of the parameter screen: class of the catalogue → title, icon, what it is. */
const GROUPS = [
  ['algorithm', 'Настройки трассировки', 'settings', 'Могут только ужесточить правила — результат всегда проходит проверку по техническим требованиям.'],
  ['data_assumption', 'Недостающие данные', 'info', 'Значений нет во входном файле — сервис восполняет их по правилу. Изменяемые можно переключить.'],
  ['norm', 'Правила проектирования', 'lock', 'Технические требования: отступы, пересечения, врезки, диаметры, стоимость. Не меняются.'],
  ['interpretation', 'Как применяются правила', 'book', 'Решения там, где правило можно прочитать по-разному.'],
];

/** Keys with a slider next to the number: the range is the range of the catalogue. */
const SLIDERS = new Set(['turn_penalty', 'extra_clearance_m', 'min_crossing_angle_deg', 'existing_flow_share']);

export function create(root) {
  let values = null;
  let valuesFor = null;
  let live = null; // {run, next, timer}
  let showAllRuns = false;
  const draft = {name: prefs.get('calcName', ''), note: prefs.get('calcNote', '')};

  const entries = () => (store.catalog ? store.catalog.params : []);
  const defaults = () => Object.fromEntries(entries().filter((e) => e.editable).map((e) => [e.key, e.default]));

  // Настройки черновика живут, пока открыт этот набор: на другом наборе и в новой сессии — значения по умолчанию,
  // чтобы следующий человек не считал с чужими параметрами, не заметив этого.
  const ensureValues = () => {
    const ds = store.route.dataset;
    if (valuesFor === ds && values) return;
    valuesFor = ds;
    values = defaults();
  };
  const saveValues = () => {};

  const changed = (e) => e.editable && String(values[e.key]) !== String(e.default);

  const control = (e) => {
    const v = values[e.key];
    if (e.type === 'enum' || e.type === 'boolean') {
      return h('div', {class: 'seg', role: 'radiogroup', 'aria-label': e.title},
        e.options.map((o) => h('button', {type: 'button', role: 'radio', 'aria-checked': String(String(v) === o.value),
          class: String(v) === o.value ? 'on' : '', dataset: {set: e.key, value: o.value, kind: e.type}}, o.label)));
    }
    if (e.type === 'number') {
      const pct = e.unit === 'доля';
      const shown = pct ? Math.round(v * 100) : v;
      const min = pct ? 0 : e.min;
      const max = pct ? 100 : e.max;
      const step = pct ? 5 : e.step;
      return [
        SLIDERS.has(e.key) ? h('input', {type: 'range', min, max, step, value: shown, 'aria-label': e.title, dataset: {num: e.key, pct: pct ? '1' : '', slider: '1'}}) : null,
        h('span', {class: 'unit-input'},
          h('input', {type: 'number', step, min, max, value: shown, 'aria-label': e.title, dataset: {num: e.key, pct: pct ? '1' : ''}}),
          h('span', null, pct ? '%' : e.unit))];
    }
    return h('span', {class: 'value'}, e.value);
  };

  const paramRow = (e) => {
    if (!paramVisible(e, values)) return null;
    const opt = (e.options || []).find((o) => String(values[e.key]) === o.value);
    return h('div', {class: 'param' + (changed(e) ? ' changed' : ''), id: 'param-' + e.key, dataset: {key: e.key}},
      h('div', {class: 'head'}, h('span', {class: 'title'}, e.title),
        changed(e) ? h('button', {type: 'button', class: 'quiet sm', dataset: {reset: e.key}}, icon('reset'), 'Сбросить') : null),
      h('div', {class: 'desc'}, e.description),
      h('div', {class: 'control'}, control(e)),
      opt && opt.effect ? h('div', {class: 'effect'}, opt.effect) : null,
      e.source ? h('div', {class: 'source'}, e.source) : null);
  };

  /** Read-only rule: name · value · source, with a lock. */
  const ruleRow = (e) => {
    const v = (e.options || []).find((o) => String(values[e.key]) === o.value);
    return h('tr', {id: 'param-' + e.key, dataset: {key: e.key}},
      h('td', null, h('span', {class: 'lock'}, icon('lock')), e.title, e.source ? h('span', {class: 'src'}, e.source) : null),
      h('td', {class: 'small', title: e.description}, v ? v.label : e.value));
  };

  const runItem = (r) => {
    const p = r.progress;
    const active = ACTIVE.includes(r.status);
    return h('div', {class: 'item', dataset: {key: r.id}},
      h('div', {class: 'grow'},
        h('div', {class: 'row'},
          r.status === 'DONE' ? h('a', {href: `#/datasets/${store.route.dataset}/runs/${r.id}/variants/1`, class: 'title'}, r.name || 'Расчёт')
            : h('span', {class: 'title'}, r.name || 'Расчёт'),
          status(r.status),
          r.pinned ? h('span', {class: 'badge outline', dataset: {tip: 'Закреплён: не удаляется автоматически по сроку хранения'}}, icon('pin'), 'Закреплён') : null),
        h('div', {class: 'small muted'}, `${when(r.created_at)}${r.best_score != null ? ` · S = ${fmt(r.best_score, 2)}` : ''}${
          r.validation_errors != null ? (r.validation_errors ? ` · нарушений правил: ${r.validation_errors}` : ' · нарушений нет') : ''}`),
        r.note ? h('div', {class: 'small', style: {fontStyle: 'italic'}}, r.note) : null,
        r.error ? callout('bad', r.error) : null,
        active ? progress(p ? p.done : 0, p ? p.total : 0, p ? `${p.stage}${p.total ? ` · ${fmt(p.done)} из ${fmt(p.total)}` : ''}` : 'в очереди') : null),
      h('div', {class: 'actions'},
        active ? iconButton('eye', 'Показать построение', {class: 'sm', dataset: {watch: r.id}}) : null,
        active ? iconButton('x', 'Отменить расчёт', {class: 'sm', dataset: {cancel: r.id}}) : null,
        !active ? iconButton('pin', r.pinned ? 'Открепить' : 'Закрепить', {class: 'sm' + (r.pinned ? ' primary' : ''), dataset: {pin: r.id, pinned: r.pinned ? '1' : ''}}) : null,
        iconButton('pencil', 'Переименовать', {class: 'sm', dataset: {rename: r.id}}),
        iconButton('note', 'Заметка', {class: 'sm', dataset: {note: r.id}}),
        !active ? iconButton('trash', 'Удалить', {class: 'sm danger', dataset: {del: r.id}}) : null));
  };

  const render = () => {
    const r = store.route;
    const o = store.overview;
    if (!r.dataset) return replace(root, empty('Набор не выбран', 'Выберите набор на шаге «Исходные данные».'));
    if (!store.catalog || !o || o.id !== r.dataset) return replace(root, h('div', {class: 'muted'}, 'Загрузка…'));
    ensureValues();
    const blocking = (o.readiness || []).filter((c) => c.blocking);
    const runs = store.runs || [];
    const running = runs.find((x) => x.status === 'RUNNING');
    const done = live && live.done ? runs.find((x) => x.id === live.run) : null;
    const editable = entries().filter((e) => e.editable);
    const nChanged = editable.filter(changed).length;
    const shownRuns = runs.slice(0, showAllRuns ? runs.length : 7);
    const ready = o.status === 'READY' && !blocking.length;
    const focusKey = r.query && r.query.param;

    const groupCards = GROUPS.map(([cls, title, ic, desc]) => {
      const list = entries().filter((e) => e.class === cls);
      if (!list.length) return null;
      const edit = list.filter((e) => e.editable);
      const fixed = list.filter((e) => !e.editable);
      const nCh = edit.filter(changed).length;
      const open = edit.length > 0 || prefs.get('calcGroup.' + cls, false) || list.some((e) => e.key === focusKey);
      return card(title, h('div', null,
        h('p', {class: 'small muted', style: {marginBottom: edit.length ? '0' : '6px'}}, desc),
        edit.length ? edit.map(paramRow) : null,
        fixed.length ? h('table', {class: 't compact rules', style: {marginTop: edit.length ? '8px' : '0'}},
          h('tbody', null, fixed.map(ruleRow))) : null),
      {collapsible: true, open, id: 'group-' + cls, icon: ic,
        count: edit.length ? `${count(edit.length, 'настройка', 'настройки', 'настроек')}${nCh ? ' · изменено ' + nCh : ''}` : `${fixed.length} · только для чтения`});
    });

    replace(root,
      h('div', {class: 'step-head'},
        h('div', {class: 'title-row'}, h('h2', null, icon('calculator'), 'Расчёт'), h('span', {class: 'spacer'}),
          h('span', {class: 'badge lg ' + (ready ? 'ok' : 'bad')}, icon(ready ? 'check' : 'alert'), ready ? 'Готов к расчёту' : 'Расчёт недоступен')),
        h('div', {class: 'sub'}, h('b', null, splitName(o.name).title), o.version > 1 ? h('span', {class: 'badge outline'}, `версия ${o.version}`) : null)),
      !ready ? callout('bad', 'Набор не готов к расчёту. ', h('a', {href: `#/datasets/${r.dataset}`}, 'К проверке данных')) : null,
      running ? card('Идёт расчёт', h('div', null, runItem(running),
        h('p', {class: 'small muted'}, anim.live ? 'Построение отображается на карте по ходу расчёта.'
          : 'Показ построения во время расчёта выключен в настройках.')), {icon: 'loader'}) : null,
      done && done.status === 'DONE' ? callout('ok', `Расчёт «${done.name}» готов. `, h('a', {href: `#/datasets/${r.dataset}/runs/${done.id}/variants/1`}, 'Открыть результат')) : null,
      h('div', {class: 'actions-row'},
        h('button', {type: 'button', class: 'primary', dataset: {act: 'start'}, disabled: !ready}, icon('play'), 'Рассчитать'),
        h('span', {class: 'small muted'}, `Все ${fmt((o.contents && o.contents.oks && o.contents.oks.count) || 0)} ОКС набора`),
        h('span', {class: 'spacer'}),
        nChanged
          ? [h('span', {class: 'badge warn'}, icon('alert'), `Изменено параметров: ${nChanged}`),
            h('button', {type: 'button', class: 'quiet sm', dataset: {act: 'reset-all'}}, icon('reset'), 'Сбросить')]
          : h('span', {class: 'badge neutral'}, 'Параметры по умолчанию')),
      nChanged > 1 ? h('p', {class: 'small muted', style: {marginTop: '-6px'}}, 'Чтобы увидеть вклад каждого параметра, меняйте их по одному и сравнивайте расчёты на вкладке «Сравнение».') : null,

      card('Название расчёта', h('div', {class: 'stack'},
        h('input', {type: 'text', dataset: {act: 'name'}, 'aria-label': 'Название расчёта',
          placeholder: 'По умолчанию — из изменённых параметров или дата',
          value: draft.name, oninput: (ev) => { draft.name = ev.target.value; prefs.set('calcName', ev.target.value); }}),
        h('textarea', {dataset: {act: 'note'}, 'aria-label': 'Заметка к расчёту', placeholder: 'Заметка: цель расчёта, что проверяем',
          oninput: (ev) => { draft.note = ev.target.value; prefs.set('calcNote', ev.target.value); }}, draft.note)),
      {collapsible: true, open: prefs.get('calcName2', false) || !!draft.name || !!draft.note, id: 'name-card', icon: 'pencil'}),

      groupCards,

      card('История расчётов',
        runs.length ? h('div', null, shownRuns.map(runItem),
          runs.length > shownRuns.length
            ? h('button', {type: 'button', class: 'quiet sm', dataset: {act: 'more-runs'}}, `Показать ещё ${runs.length - shownRuns.length}`, icon('chevron-down'))
            : null)
          : empty('Расчётов нет', 'Запустите расчёт — результат сохранится в истории.'),
        {count: runs.length || '', icon: 'calculator'}));

    const remember = (id, key) => {
      const d = root.querySelector('#' + id);
      if (d && d.tagName === 'DETAILS') d.addEventListener('toggle', () => prefs.set(key, d.open));
    };
    remember('name-card', 'calcName2');
    for (const [cls] of GROUPS) remember('group-' + cls, 'calcGroup.' + cls);
    if (focusKey) {
      const el = root.querySelector('#param-' + focusKey);
      if (el) setTimeout(() => el.scrollIntoView({block: 'center'}), 50);
    }
    syncLive(running);
  };

  // ---------------------------------------------------------------- live construction

  const syncLive = (running) => {
    if (running && (!live || live.run !== running.id) && anim.live) watch(running.id);
    if (live && live.done && !running && !runsHave(live.run)) live = null;
  };

  const watch = (runId) => {
    stopLive();
    live = {run: runId, next: 0, opened: false};
    const tick = async () => {
      if (!live || live.run !== runId) return;
      try {
        const j = await api.journal(runId, '1', live.next);
        if (!live || live.run !== runId) return;
        if (j.live) {
          if (!live.opened && j.events.length) {
            player.openBuild(j.events, {title: 'Построение во время расчёта', live: true, onClose: () => { if (live) live.closed = true; }});
            live.opened = true;
          } else if (live.opened && !live.closed) {
            player.appendLive(j.events, false);
          }
          live.next = j.next;
          live.timer = setTimeout(tick, 1000);
        } else if (j.status === 'QUEUED' || j.status === 'RUNNING') {
          live.timer = setTimeout(tick, 1000);
        } else {
          if (live.opened && !live.closed) player.appendLive([], true);
          live.done = true;
          toast(j.status === 'DONE' ? 'Расчёт готов' : j.status === 'CANCELLED' ? 'Расчёт отменён' : 'Расчёт завершился с ошибкой', j.status !== 'DONE');
          loadRuns(store.route.dataset);
          loadDatasets();
        }
      } catch (e) {
        live.timer = setTimeout(tick, 2000);
      }
    };
    tick();
  };

  const runsHave = (id) => (store.runs || []).some((x) => x.id === id);

  const stopLive = () => {
    if (live) clearTimeout(live.timer);
    live = null;
  };

  // ---------------------------------------------------------------- actions

  const setValue = (key, raw, kind) => {
    values[key] = kind === 'boolean' ? raw === 'true' : raw;
    saveValues();
    render();
  };
  on(root, 'click', '[data-set]', (e, t) => setValue(t.dataset.set, t.dataset.value, t.dataset.kind));
  const setNumber = (t, rerender) => {
    const raw = String(t.value).replace(',', '.').trim();
    const e = entries().find((x) => x.key === t.dataset.num);
    if (raw === '' || Number.isNaN(Number(raw))) {
      // пустое или нечисловое поле не превращается в 0: возвращаем прежнее значение
      if (rerender) render();
      return;
    }
    let v = Number(raw);
    if (e && !t.dataset.pct) v = Math.min(e.max, Math.max(e.min, v));
    if (t.dataset.pct) v = Math.min(100, Math.max(0, v)) / 100;
    values[t.dataset.num] = v;
    saveValues();
    if (rerender) render();
    else {
      // the slider and the field of one parameter stay in step without a redraw
      for (const x of root.querySelectorAll(`[data-num="${t.dataset.num}"]`)) if (x !== t) x.value = t.value;
    }
  };
  on(root, 'input', '[data-slider]', (e, t) => setNumber(t, false));
  on(root, 'change', '[data-num]', (e, t) => setNumber(t, true));
  on(root, 'click', '[data-reset]', (e, t) => {
    values[t.dataset.reset] = entries().find((x) => x.key === t.dataset.reset).default;
    saveValues();
    render();
  });
  on(root, 'click', '[data-act=reset-all]', () => {
    values = defaults();
    saveValues();
    render();
  });
  on(root, 'click', '[data-act=more-runs]', () => { showAllRuns = true; render(); });
  on(root, 'click', '[data-act=start]', async (e, t) => {
    t.disabled = true;
    const body = {};
    for (const x of entries()) if (x.editable) body[x.key] = values[x.key];
    const name = draft.name.trim();
    const note = draft.note.trim();
    if (name) body.name = name;
    if (note) body.note = note;
    try {
      const r = await api.startRun(store.route.dataset, body);
      draft.name = '';
      draft.note = '';
      prefs.set('calcName', '');
      prefs.set('calcNote', '');
      toast('Расчёт поставлен в очередь');
      live = null;
      if (anim.live) watch(r.id);
      await loadRuns(store.route.dataset);
    } catch (err) {
      t.disabled = false;
      toast('Не удалось запустить: ' + err.message, true);
    }
  });
  on(root, 'click', '[data-watch]', (e, t) => {
    live = null;
    watch(t.dataset.watch);
  });
  on(root, 'click', '[data-cancel]', async (e, t) => {
    try { await api.cancelRun(t.dataset.cancel); loadRuns(store.route.dataset); } catch (err) { toast(err.message, true); }
  });
  on(root, 'click', '[data-del]', async (e, t) => {
    const r = store.runs.find((x) => x.id === t.dataset.del);
    if (!await confirmDialog('Удалить расчёт?', `«${r ? r.name : 'Расчёт'}» будет удалён вместе с результатом.`)) return;
    try { await api.deleteRun(t.dataset.del); toast('Расчёт удалён'); loadRuns(store.route.dataset); } catch (err) { toast(err.message, true); }
  });
  on(root, 'click', '[data-pin]', async (e, t) => {
    try { await api.updateRun(t.dataset.pin, {pinned: !t.dataset.pinned}); loadRuns(store.route.dataset); } catch (err) { toast(err.message, true); }
  });
  on(root, 'click', '[data-rename]', async (e, t) => {
    const r = store.runs.find((x) => x.id === t.dataset.rename);
    const name = await promptDialog('Название расчёта', {value: r ? r.name : ''});
    if (name == null) return;
    try { await api.updateRun(t.dataset.rename, {name}); loadRuns(store.route.dataset); } catch (err) { toast(err.message, true); }
  });
  on(root, 'click', '[data-note]', async (e, t) => {
    const r = store.runs.find((x) => x.id === t.dataset.note);
    const note = await promptDialog('Заметка к расчёту', {value: r && r.note ? r.note : '', multiline: true, placeholder: 'Цель расчёта, что проверяем'});
    if (note == null) return;
    try { await api.updateRun(t.dataset.note, {note}); loadRuns(store.route.dataset); } catch (err) { toast(err.message, true); }
  });

  subscribe('runs', () => store.route.step === 'calc' && keepFocus(root, render));
  subscribe('overview', () => store.route.step === 'calc' && keepFocus(root, render));
  subscribe('catalog', () => store.route.step === 'calc' && keepFocus(root, render));

  return {
    update() {
      if (store.route.dataset) loadRuns(store.route.dataset);
      render();
    },
    leave() {
      stopLive();
    },
  };
}

