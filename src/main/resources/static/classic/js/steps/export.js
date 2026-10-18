// Result, tab «Выгрузка» — what can be downloaded, what it is for and how large it is. Files are saved only when
// the user presses the button.

import {h, on, replace} from '../core/dom.js';
import {bytes, when} from '../core/format.js';
import {go} from '../core/router.js';
import {store, subscribe} from '../core/store.js';
import {loadRun} from '../core/data.js';
import {card, empty, icon, toast} from '../ui/components.js';

export function create(root) {
  const sizes = {};

  /** Размер файла — запросом HEAD; у потоковых ответов размера нет заранее. */
  const measure = async (url) => {
    if (sizes[url] !== undefined) return;
    sizes[url] = null;
    try {
      const r = await fetch(url, {method: 'HEAD'});
      const len = r.headers.get('content-length');
      sizes[url] = len ? Number(len) : -1;
    } catch (e) {
      sizes[url] = -1;
    }
    render();
  };

  const file = (ic, title, purpose, url, size, name, extra, main) => h('div', {class: 'file-row'},
    icon(ic, 'lg'),
    h('div', {class: 'grow'}, h('div', {class: 'title'}, title, main ? h('span', {class: 'badge accent'}, 'Основной') : null),
      h('div', {class: 'small muted'}, purpose, size > 0 ? ` · ${bytes(size)}` : '')),
    h('div', {class: 'row nowrap'}, extra,
      h('a', {class: 'button' + (main ? ' primary' : ''), href: url, download: name}, icon('download'), 'Скачать')));

  const render = () => {
    const r = store.route;
    const run = store.run;
    if (!r.run) return replace(root, empty('Нет результата', 'Выгрузка доступна после расчёта.'));
    if (!run || run.id !== r.run) return replace(root, h('div', {class: 'muted'}, 'Загрузка…'));
    if (run.status !== 'DONE') return replace(root, empty('Результата пока нет', 'Выгрузка доступна после завершения расчёта.'));
    const v = r.variant || '1';
    const s = run.summary.find((x) => x.variant_id === v) || run.summary[0];
    const short = run.id.slice(0, 8);
    const variantUrl = `/api/runs/${run.id}/variants/${v}.geojson?derived=false&download=true`;
    const reportUrl = `/api/runs/${run.id}/variants/${v}/report.html?download=true`;
    const journalUrl = `/api/runs/${run.id}/journal?variant=${v}`;
    measure(variantUrl);
    replace(root,
      h('div', {class: 'step-head'},
        h('div', {class: 'title-row'}, h('h2', null, icon('download'), 'Выгрузка')),
        h('div', {class: 'sub'}, h('b', null, run.name), h('span', {class: 'muted'}, when(run.created_at)))),
      h('div', {class: 'variant-tabs'}, run.summary.slice().sort((a, b) => a.rank - b.rank).map((x) =>
        h('button', {type: 'button', class: 'variant-tab' + (x.variant_id === v ? ' on' : ''), dataset: {variant: x.variant_id, key: 'v' + x.variant_id}},
          h('span', {class: 'r'}, x.rank === 1 ? icon('trophy') : null, `Вариант ${x.variant_id}`), h('span', {class: 's'}, `${x.rank} место`)))),
      card('Файлы', h('div', null,
        file('file', 'Результат расчёта (GeoJSON)', 'Все варианты в формате обмена: новые участки, камеры, технические узлы, сводка каждого варианта',
          `/api/runs/${run.id}/result.geojson`, run.result_bytes, `result-${short}.geojson`, null, true),
        file('map', `Вариант ${v} (GeoJSON)`, 'Только выбранный вариант в том же формате — для ГИС',
          variantUrl, sizes[variantUrl], `variant-${short}-v${v}.geojson`),
        file('file', `Отчёт по варианту ${v} (HTML)`, 'Сводка, карта, показатели, ОКС, проверка правил, параметры — страница для печати',
          reportUrl, -1, `report-${short}-v${v}.html`,
          h('a', {class: 'button', href: `/api/runs/${run.id}/variants/${v}/report.html`, target: '_blank', rel: 'noopener'}, icon('external'), 'Открыть')),
        file('route', `Журнал построения (JSON)`, `Порядок подключения ОКС и оценки мест врезки варианта ${v} — для разбора решений`,
          journalUrl, -1, `journal-${short}-v${v}.json`)), {icon: 'download'}),
      h('p', {class: 'small muted'}, `Выбран вариант ${v} (${s.rank} место). Служебные слои карты в выгрузку не входят.`));
  };

  on(root, 'click', '[data-variant]', (e, t) => go({variant: t.dataset.variant}));
  subscribe('run', () => store.route.step === 'export' && render());

  return {
    update() {
      const r = store.route;
      if (r.run && (!store.run || store.run.id !== r.run)) loadRun(r.run).catch((e) => toast(e.message, true));
      render();
    },
  };
}
