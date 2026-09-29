// Step 1 — data: the datasets grouped (projects first, examples collapsed), upload by drag and drop
// with progress and cancel. After an upload the check of the dataset opens by itself.

import {api} from '../core/api.js';
import {h, on, replace} from '../core/dom.js';
import {ACTIVE, bytes, count, fmt, when} from '../core/format.js';
import {go} from '../core/router.js';
import {prefs, store, subscribe} from '../core/store.js';
import {loadDatasets} from '../core/data.js';
import {confirmDialog, empty, icon, iconButton, progress, status, toast} from '../ui/components.js';

/**
 * The name of a dataset is the name of its file; the seeding script (tools/seed_datasets.py) puts the group in
 * front of a middle dot: «Проект · ЗИЛ». Everything else is «Загруженные».
 */
const GROUPS = [
  ['Проект', 'Проекты', 'map'],
  ['Загруженные', 'Загруженные наборы', 'upload'],
  ['Пример', 'Примеры', 'flask'],
  ['Нагрузка', 'Большие наборы', 'gauge'],
];

export function splitName(name) {
  const m = /^(Проект|Пример|Нагрузка)\s·\s(.+)$/.exec(name || '');
  return m ? {group: m[1], title: m[2]} : {group: 'Загруженные', title: name || ''};
}

export function create(root) {
  let upload = null; // {name, loaded, total, abort}
  let importProgress = {};

  const render = () => {
    const all = store.datasets || [];
    const roots = all.filter((d) => d.root_id === d.id || !d.parent_id);
    const zone = h('div', {class: 'dropzone', dataset: {act: 'zone'}},
      h('input', {type: 'file', accept: '.geojson,.json,application/geo+json,application/json', dataset: {act: 'file'}}),
      icon('upload', 'lg'),
      upload
        ? h('div', {class: 'grow'}, h('div', null, `Загрузка: ${upload.name}`), progress(upload.loaded, upload.total, `${bytes(upload.loaded)} из ${bytes(upload.total)}`),
          h('button', {type: 'button', class: 'sm', dataset: {act: 'abort'}}, 'Отменить'))
        : h('div', {class: 'grow'},
          h('div', {style: {fontWeight: 600}}, 'Перетащите файл GeoJSON или ', h('button', {type: 'button', class: 'quiet', dataset: {act: 'pick'}, style: {padding: '0 2px', minHeight: 0}}, 'выберите файл')),
          h('div', {class: 'small muted'}, 'FeatureCollection в EPSG:4326, до 3 ГБ: сеть, камеры, источник, ОКС с точками подключения, здания, ограничения')));

    const cardOf = (d) => {
      const versions = all.filter((v) => v.root_id === d.id && v.id !== d.id).sort((a, b) => a.version - b.version);
      const tc = d.type_counts || {};
      const prog = ACTIVE.includes(d.status) && importProgress[d.id];
      const {title} = splitName(d.name);
      const oks = tc.oks_connection_point != null ? tc.oks_connection_point : tc.oks_future;
      return h('div', {class: 'ds-card' + (store.route.dataset === d.id ? ' sel' : ''), dataset: {open: d.id, key: d.id}, tabindex: 0, role: 'button'},
        h('div', {class: 'head'},
          h('span', {class: 'name'}, title),
          status(d.status)),
        h('div', {class: 'meta'},
          d.feature_count != null ? h('span', null, count(d.feature_count, 'объект', 'объекта', 'объектов')) : null,
          oks != null ? [h('span', null, '·'), h('span', null, count(oks, 'ОКС', 'ОКС', 'ОКС'))] : null,
          tc.heat_network != null ? [h('span', null, '·'), h('span', null, count(tc.heat_network, 'участок сети', 'участка сети', 'участков сети'))] : null,
          tc.restriction != null ? [h('span', null, '·'), h('span', null, count(tc.restriction, 'ограничение', 'ограничения', 'ограничений'))] : null),
        h('div', {class: 'meta'},
          h('span', null, `${when(d.created_at)} · ${bytes(d.file_bytes)}`),
          d.run_count ? [h('span', null, '·'), h('span', null, count(d.run_count, 'расчёт', 'расчёта', 'расчётов'))] : null,
          h('span', {class: 'spacer'}),
          ACTIVE.includes(d.status) ? h('button', {type: 'button', class: 'sm', dataset: {cancel: d.id}}, icon('x'), 'Отменить импорт') : null,
          iconButton('trash', 'Удалить набор', {class: 'sm danger', dataset: {del: d.id}})),
        d.error ? h('div', {class: 'callout bad small'}, icon('alert-circle'), d.error) : null,
        prog ? progress(prog.read, prog.total, `прочитано ${fmt(100 * prog.read / prog.total, 0)} %`) : null,
        versions.length ? h('div', {class: 'versions'}, versions.map((v) => h('div', {class: 'v'},
          h('a', {href: `#/datasets/${v.id}`, dataset: {stop: '1'}}, `Версия ${v.version}`),
          h('span', {class: 'muted'}, `${v.edits ? count(v.edits.length, 'правка', 'правки', 'правок') : ''}${v.note ? ' — ' + v.note : ''}`),
          status(v.status),
          v.run_count ? h('span', {class: 'muted'}, count(v.run_count, 'расчёт', 'расчёта', 'расчётов')) : null))) : null);
    };

    const byGroup = {};
    for (const d of roots) (byGroup[splitName(d.name).group] = byGroup[splitName(d.name).group] || []).push(d);
    const sections = [];
    for (const [key, title, ic] of GROUPS) {
      const list = byGroup[key];
      if (!list || !list.length) continue;
      const collapsible = key === 'Пример' || key === 'Нагрузка';
      const open = !collapsible || prefs.get('dsGroup.' + key, false);
      const head = h('div', {class: 'ds-group'}, icon(ic), h('span', {class: 'label'}, `${title} · ${list.length}`),
        collapsible ? h('button', {type: 'button', class: 'quiet sm', dataset: {group: key}}, open ? 'Свернуть' : 'Показать', icon(open ? 'chevron-down' : 'chevron-right')) : null);
      sections.push(head);
      if (open) sections.push(list.map((d) => cardOf(d)));
    }
    replace(root,
      h('div', {class: 'step-head'}, h('div', {class: 'title-row'}, h('h2', null, icon('database'), 'Исходные данные'))),
      zone,
      sections.length ? sections : empty('Наборов пока нет', 'Загрузите файл — после импорта откроется проверка данных.'));
  };

  const start = (file) => {
    if (!file) return;
    const task = api.upload(file, (loaded, total) => {
      if (upload) Object.assign(upload, {loaded, total});
      render();
    });
    upload = {name: file.name, loaded: 0, total: file.size, abort: task.abort};
    render();
    task.promise.then((r) => {
      upload = null;
      toast(`Файл «${file.name}» передан, идёт импорт`);
      loadDatasets();
      go({step: 'check', dataset: r.id});
    }).catch((e) => {
      upload = null;
      render();
      if (!e.aborted) toast('Загрузка не удалась: ' + e.message, true);
    });
  };

  on(root, 'click', '[data-act=pick]', () => root.querySelector('[data-act=file]').click());
  on(root, 'change', '[data-act=file]', (e, t) => start(t.files[0]));
  on(root, 'click', '[data-act=abort]', () => upload && upload.abort());
  on(root, 'click', '[data-group]', (e, t) => {
    prefs.set('dsGroup.' + t.dataset.group, !prefs.get('dsGroup.' + t.dataset.group, false));
    render();
  });
  root.addEventListener('dragover', (e) => {
    const z = e.target.closest('[data-act=zone]');
    if (!z) return;
    e.preventDefault();
    z.classList.add('over');
  });
  root.addEventListener('dragleave', (e) => {
    const z = e.target.closest('[data-act=zone]');
    if (z) z.classList.remove('over');
  });
  root.addEventListener('drop', (e) => {
    const z = e.target.closest('[data-act=zone]');
    if (!z) return;
    e.preventDefault();
    z.classList.remove('over');
    start(e.dataTransfer.files[0]);
  });
  on(root, 'click', '[data-open]', (e, t) => {
    if (e.target.closest('[data-del],[data-cancel],[data-stop]')) return;
    go({step: 'check', dataset: t.dataset.open});
  });
  on(root, 'keydown', '[data-open]', (e, t) => { if (e.key === 'Enter' && e.target === t) go({step: 'check', dataset: t.dataset.open}); });
  on(root, 'click', '[data-del]', async (e, t) => {
    e.stopPropagation();
    const d = (store.datasets || []).find((x) => x.id === t.dataset.del);
    if (!await confirmDialog('Удалить набор?', `«${splitName(d ? d.name : '').title}» будет удалён вместе с версиями и всеми расчётами. Это нельзя отменить.`)) return;
    try {
      await api.deleteDataset(t.dataset.del);
      toast('Набор удалён');
      if (store.route.dataset && (store.datasets.find((x) => x.id === store.route.dataset) || {}).root_id === t.dataset.del) go({step: 'data', dataset: null});
      loadDatasets();
    } catch (err) { toast(err.message, true); }
  });
  on(root, 'click', '[data-cancel]', async (e, t) => {
    e.stopPropagation();
    try { await api.cancelImport(t.dataset.cancel); loadDatasets(); } catch (err) { toast(err.message, true); }
  });

  /** Ход разбора загружаемых наборов; возвращает true, только если он действительно сдвинулся. */
  const poll = async () => {
    const active = (store.datasets || []).filter((d) => ACTIVE.includes(d.status));
    let changed = false;
    for (const d of active) {
      try {
        const x = await api.dataset(d.id);
        if (x.progress && x.progress.bytes_total) {
          const now = {read: x.progress.bytes_read, total: x.progress.bytes_total};
          const was = importProgress[d.id];
          if (!was || was.read !== now.read || was.total !== now.total) {
            importProgress[d.id] = now;
            changed = true;
          }
        }
      } catch (e) { /* пропускаем: набор мог исчезнуть */ }
    }
    return changed;
  };
  subscribe('datasets', () => {
    if (store.route.step !== 'data') return;
    render();
    poll().then((changed) => changed && render());
  });

  return {
    update() {
      loadDatasets().catch((e) => toast(e.message, true));
      render();
    },
  };
}
