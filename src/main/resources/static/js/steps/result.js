// Step 4 — result: variants of a calculation in words and figures, load of the existing network and the length limit, the OKS
// list, the card of an OKS (path, why this tie-in, how the route was searched), check of the mandatory rules,
// replay of the construction.

import {api} from '../core/api.js';
import {h, keepFocus, on, replace} from '../core/dom.js';
import {ACTIVE, count, fmt, metres, natural, rub, when} from '../core/format.js';
import {go} from '../core/router.js';
import {store, subscribe} from '../core/store.js';
import {loadJournal, loadRun, loadRuns} from '../core/data.js';
import {callout, card, empty, icon, iconButton, metric, paramVisible, promptDialog, status, toast, usageBar} from '../ui/components.js';
import {TARGET_TITLES, term} from '../ui/glossary.js';
import {clearHighlight, highlight} from '../map/map.js';
import * as player from '../anim/player.js';
import {splitName} from './data.js';

const COSTS = [
  ['segment_cost', 'Новые участки', 'cost-seg'],
  ['chamber_construction_cost', 'Новые камеры', 'cost-chamber'],
  ['existing_chamber_tie_in_cost', 'Врезки в камеры', 'cost-tie'],
  ['unconnected_penalty', 'Штраф за неподключённые', 'cost-penalty'],
];

const SPECIAL_TITLES = {road: 'дорога', tram_tracks: 'трамвайные пути', gas_pipeline: 'газопровод', power_cable: 'кабель', heat_network: 'существующая теплосеть'};

const HINTS = {
  NO_ROUTE: 'Проверьте на карте, что мешает рядом с этим ОКС. Может помочь правка данных, если ограничение в наборе лишнее или неточное.',
  LENGTH_LIMIT: 'Ни один допустимый ДУ не укладывается в предельную длину. Ближе к точке подключения допустимого места врезки не нашлось — проверьте сеть и ограничения рядом.',
  SEARCH_LIMIT: 'Район очень плотный — поиск остановлен по пределу. Посмотрите, как искалась трасса и что её ограничивает.',
  FLOW_EXCEEDS_MAX_DN: 'Расход больше пропускной способности самого большого ДУ — проверьте расход ОКС в данных.',
  NO_CONNECTION_POINT: 'У ОКС нет точки подключения — её нужно добавить в данные.',
  NO_NETWORK: 'Рядом нет сети, связанной с источником — см. проверку данных.',
  CANCELLED: 'Расчёт был остановлен.',
};

export function create(root) {
  let showAllOks = false;
  let onlyUnconnected = false;
  let journalFor = null;
  let journal = null;

  const variantOf = (run, v) => (run && run.summary ? run.summary.find((x) => x.variant_id === v) : null);

  /** Short description of how an OKS is fed: own tie-in, or a branching of the new network fed by a tie-in. */
  const joinsShort = (o) => (o.joins === 'tie_in'
    ? h('span', {class: 'nowrap', dataset: {tip: `Своя врезка ${o.tie_object_type === 'heat_chamber' ? 'в существующую камеру' : 'в участок'} ${o.tie_object_id}`}}, icon('merge', 'sm'), ` врезка ${o.tie_object_id}`)
    : h('span', {class: 'nowrap', dataset: {tip: `К новой сети через разветвление; питается от врезки ${o.tie_object_id}`}}, icon('circle-dot', 'sm'), ` от ${o.tie_object_id}`));
  const joinsText = (o) => (o.joins === 'tie_in'
    ? `врезка ${o.tie_object_type === 'heat_chamber' ? 'в камеру' : 'в участок'} ${o.tie_object_id}`
    : `к новой сети через разветвление (врезка ${o.tie_object_id})`);

  /** Derived layers of the variant on the map: flow changes, length runs. */
  const derived = () => {
    const data = store.variantData;
    const feats = data ? data.features : [];
    const flows = feats.filter((f) => f.properties.object_type === 'flow_change');
    const runs = feats.filter((f) => f.properties.object_type === 'length_run');
    const usageOf = {};
    for (const f of runs) {
      let segs = f.properties.segments;
      if (typeof segs === 'string') { try { segs = JSON.parse(segs); } catch (e) { segs = segs.split(','); } }
      for (const id of segs || []) usageOf[id] = f.properties;
    }
    // один существующий участок может прийти несколькими кусками: в таблице — одна строка на участок
    const bySeg = new Map();
    for (const f of flows.filter((x) => x.properties.reconstructed)) {
      const p = f.properties;
      const was = bySeg.get(p.existing_object_id);
      if (!was || p.calculated_flow_tph > was.properties.calculated_flow_tph) bySeg.set(p.existing_object_id, f);
    }
    const specials = feats.filter((f) => f.properties.object_type === 'heat_network' && f.properties.laying_method === 'special');
    return {flows, recon: [...bySeg.values()], runs, usageOf, specials};
  };

  const render = () => {
    const r = store.route;
    const run = store.run;
    if (!r.run) return replace(root, empty('Нет результата', 'Запустите расчёт на шаге «Расчёт».', h('button', {type: 'button', onclick: () => go({step: 'calc'})}, icon('calculator'), 'К расчёту')));
    if (!run || run.id !== r.run) return replace(root, h('div', {class: 'muted'}, 'Загрузка…'));
    const head = header(run);
    if (run.status !== 'DONE') {
      return replace(root, head, card(ACTIVE.includes(run.status) ? 'Расчёт выполняется' : 'Результата нет', h('div', null,
        ACTIVE.includes(run.status) ? h('p', null, 'Расчёт ещё идёт. ', h('a', {href: `#/datasets/${r.dataset}/calculate`}, 'Показать ход'))
          : callout('bad', run.status === 'FAILED' ? 'Расчёт завершился с ошибкой: ' + (run.error || '') : 'Расчёт отменён.'))));
    }
    const vs = run.summary.slice().sort((a, b) => a.rank - b.rank);
    const v = variantOf(run, r.variant) || vs[0];
    if (r.oks) return replace(root, head, oksCard(run, v, r.oks));
    const d = derived();
    replace(root, head, variantTabs(vs, v), overview(run, v, d), reconstruction(d), specialsCard(v, d), lengthLimit(d), oksList(v));
  };

  const header = (run) => {
    const diff = paramDiff(run.params);
    return h('div', {class: 'step-head'},
      h('div', {class: 'title-row'}, h('h2', null, icon('map'), 'Результат'), h('span', {class: 'spacer'}), status(run.status)),
      h('div', {class: 'sub'},
        h('b', null, run.name || 'Расчёт'),
        run.pinned ? h('span', {class: 'badge outline'}, icon('pin'), 'Закреплён') : null,
        h('span', {class: 'btn-group'},
          iconButton('pencil', 'Переименовать', {class: 'sm', dataset: {act: 'rename'}}),
          iconButton('note', 'Заметка', {class: 'sm', dataset: {act: 'note'}}),
          iconButton('pin', run.pinned ? 'Открепить' : 'Закрепить', {class: 'sm' + (run.pinned ? ' primary' : ''), dataset: {act: 'pin'}}))),
      h('div', {class: 'sub muted'}, `Набор: ${splitName(run.dataset_name).title}${run.dataset_version > 1 ? ` · версия ${run.dataset_version}` : ''} · ${when(run.created_at)}`),
      run.note ? h('div', {class: 'small', style: {fontStyle: 'italic'}}, run.note) : null,
      diff.length ? h('div', {class: 'chips'}, h('span', {class: 'small muted'}, 'Параметры:'), diff.map((d) => h('span', {class: 'chip on', dataset: {tip: d.title}}, d.label)))
        : h('div', {class: 'small muted'}, 'Параметры по умолчанию'));
  };

  const paramDiff = (params) => {
    if (!params || !store.catalog) return [];
    const out = [];
    for (const e of store.catalog.params) {
      if (!e.editable || params[e.key] == null || String(params[e.key]) === String(e.default)) continue;
      if (!paramVisible(e, params)) continue;
      const o = (e.options || []).find((x) => x.value === String(params[e.key]));
      const n = Number(params[e.key]);
      const num = fmt(n, Number.isInteger(n) ? 0 : 2) + (e.unit === '°' ? '°' : e.unit ? ' ' + e.unit : '');
      out.push({title: e.title, label: o ? `${e.title}: ${o.label}` : `${e.title}: ${e.unit === 'доля' ? fmt(n * 100) + ' %' : num}`});
    }
    return out;
  };

  const variantTabs = (vs, v) => h('div', {class: 'variant-tabs', role: 'tablist'},
    vs.map((x) => h('button', {type: 'button', role: 'tab', 'aria-selected': String(x.variant_id === v.variant_id), class: 'variant-tab' + (x.variant_id === v.variant_id ? ' on' : ''),
      dataset: {variant: x.variant_id, key: 'v' + x.variant_id}},
    h('span', {class: 'r'}, x.rank === 1 ? icon('trophy') : null, `Вариант ${x.variant_id}`),
    h('span', {class: 's'}, 'S ' + fmt(x.score, 2)),
    h('span', {class: 'c'}, `${x.rank} место · ${fmt(x.calculated_cost / 1e6, 1)} млн ₽`, h('br'), x.strategy === 'separate' ? 'раздельное' : 'совместное'))));

  const overview = (run, v, d) => {
    const total = COSTS.reduce((a, [k]) => a + (v[k] || 0), 0) || 1;
    const val = run.validation || {};
    const unconnected = v.unconnected || [];
    const rules = val.checked ? Object.keys(val.checked).length : 0;
    return h('div', null,
      h('div', {class: 'actions-row'},
        h('button', {type: 'button', class: 'primary', dataset: {act: 'export'}}, icon('download'), 'Выгрузить'),
        h('button', {type: 'button', dataset: {act: 'replay'}}, icon('play'), 'Построение'),
        h('button', {type: 'button', dataset: {act: 'compare'}}, icon('compare'), 'Сравнить')),
      h('p', {class: 'lead-sentence'}, v.plain),
      h('div', {class: 'metrics'},
        metric(fmt(v.score, 2), 'Оценка S', 'S = 0,7 · стоимость / 25 млн + 0,3 · длина / 100 м; меньше — лучше'),
        metric(fmt(v.calculated_cost / 1e6, 1), 'Стоимость, млн ₽'),
        metric(metres(v.new_network_length), 'Новая сеть'),
        metric(fmt(v.tie_ins), 'Врезок', `из них в существующие камеры: ${fmt(v.existing_chamber_tie_in_count)}`),
        metric(fmt(v.new_chambers), 'Новых камер', `в местах врезки: ${fmt(v.new_chambers - v.branching_chambers)}, разветвлений: ${fmt(v.branching_chambers)}`),
        (() => { const n = store.variantData ? d.recon.length : v.capacity_shortfalls;
          return metric(fmt(n), 'Не хватит ДУ', 'Участков существующей сети, которым не хватит пропускной способности после подключения. В стоимость не входит', n ? 'bad' : ''); })()),
      h('div', {class: 'costbar', 'aria-label': 'Структура стоимости'}, COSTS.filter(([k]) => v[k] > 0).map(([k, t, c]) =>
        h('span', {class: c, style: {width: 100 * v[k] / total + '%'}, dataset: {tip: `${t}: ${rub(v[k])}`}}))),
      h('div', {class: 'legend-row'}, h('span', {class: 'muted'}, 'млн ₽:'), COSTS.filter(([k]) => v[k] > 0).map(([k, t, c]) =>
        h('span', null, h('i', {class: 'dot ' + c}), `${t} ${fmt(v[k] / 1e6, 1)}`))),
      h('div', {class: 'row', style: {margin: '10px 0 4px'}},
        h('span', {class: 'badge lg ' + (val.errors ? 'bad' : 'ok'), dataset: {tip: 'Результат перечитан из файла выгрузки и проверен по обязательным правилам независимо от расчёта'}},
          icon(val.errors ? 'alert' : 'shield-check'), val.errors ? `Проверка правил: нарушений ${val.errors}` : `Проверка правил пройдена${rules ? ` · ${rules} правил` : ''}`),
        unconnected.length ? h('button', {type: 'button', class: 'badge lg warn', dataset: {act: 'unconnected'}, style: {cursor: 'pointer'}}, icon('alert'), `Не подключены: ${unconnected.length} ОКС`) : null),
      val.errors ? h('details', {class: 'more'}, h('summary', null, `Нарушения (${val.errors})`),
        h('pre', {class: 'small code', style: {whiteSpace: 'pre-wrap'}}, (val.violations || []).join('\n'))) : null,
      h('div', {class: 'kv', style: {marginTop: '8px'}},
        h('span', null, 'Способ'), h('span', null, v.approach || v.explanation),
        h('span', null, 'Частей сети'), h('span', null, fmt(v.network_parts)),
        h('span', null, 'Спецпроходы'), h('span', null, v.special_segments ? count(v.special_segments, 'участок', 'участка', 'участков') : 'нет'),
        h('span', null, 'Поворотов'), h('span', null, `${fmt(v.turns)} · ${fmt(v.turns_per_km, 1)} на км`)));
  };

  const reconstruction = (d) => {
    if (!store.variantData) return null;
    if (!d.recon.length) return card('Пропускная способность существующей сети', callout('ok', 'Пропускной способности существующей сети хватает.'), {icon: 'wrench'});
    const rows = d.recon.slice().sort((a, b) => natural(a.properties.existing_object_id, b.properties.existing_object_id));
    return card('Пропускная способность существующей сети', h('div', null,
      h('p', {class: 'small muted'}, 'Этим участкам не хватит пропускной способности после подключения — для них понадобится больший ДУ. В стоимость варианта это не входит: по техническим требованиям существующая сеть не перекладывается.'),
      h('table', {class: 't compact'}, h('thead', null, h('tr', null, h('th', null, 'Участок'), h('th', {class: 'num'}, 'Расход, т/ч'), h('th', {class: 'num'}, 'ДУ'))),
        h('tbody', null, rows.map((f) => h('tr', null, h('td', null, f.properties.existing_object_id),
          h('td', {class: 'num'}, `${fmt(f.properties.existing_flow_tph, 1)} → ${fmt(f.properties.calculated_flow_tph, 1)}`),
          h('td', {class: 'num'}, `${f.properties.existing_diameter} → ${f.properties.required_diameter}`)))))),
    {icon: 'wrench', count: count(d.recon.length, 'участок', 'участка', 'участков'), cls: ''});
  };

  const specialsCard = (v, d) => {
    if (!store.variantData || !d.specials.length) return null;
    const owners = (id) => (v.oks || []).filter((o) => o.connected && o.path_segment_ids.includes(id));
    return card('Спецпроходы', h('div', null,
      h('p', {class: 'small muted'}, 'Участки, где трасса пересекает дорогу, трамвайные пути, газопровод или кабель. Угол пересечения — не меньше заданного в настройках расчёта (по правилам — 45°).'),
      h('table', {class: 't compact'}, h('thead', null, h('tr', null, h('th', null, 'Участок'), h('th', null, 'Пересекает'), h('th', {class: 'num'}, 'ДУ'), h('th', {class: 'num'}, 'Длина, м'), h('th', {class: 'num'}, 'Млн ₽'))),
        h('tbody', null, d.specials.map((f) => {
          const o = owners(f.properties.id);
          const kinds = [...new Set(o.flatMap((x) => x.specials))].map((x) => SPECIAL_TITLES[x] || x).join(', ');
          return h('tr', {class: 'click', dataset: {seg: f.properties.id}},
            h('td', {class: 'small'}, f.properties.id), h('td', {class: 'small'}, kinds || '—'),
            h('td', {class: 'num'}, f.properties.diameter), h('td', {class: 'num'}, fmt(f.properties.length, 0)),
            h('td', {class: 'num'}, fmt(f.properties.cost / 1e6, 2)));
        })))),
    {icon: 'route', count: count(d.specials.length, 'участок', 'участка', 'участков'), collapsible: true, open: true, id: 'specials-card'});
  };

  const lengthLimit = (d) => {
    if (!store.variantData || !d.runs.length) return null;
    const rows = d.runs.slice().sort((a, b) => (b.properties.usage || 0) - (a.properties.usage || 0));
    const shown = rows.slice(0, 6);
    const over = rows.filter((f) => f.properties.usage > 1).length;
    return card(['Предельная длина'], h('div', null,
      h('p', {class: 'small muted'}, 'Непрерывные части новой сети одного ДУ и доля от предельной длины для этого ДУ.'),
      h('table', {class: 't compact'}, h('thead', null, h('tr', null, h('th', null, 'ДУ'), h('th', {class: 'num'}, 'Длина, м'), h('th', {class: 'num'}, 'Предел, м'), h('th', null, 'Использовано'))),
        h('tbody', null, shown.map((f) => h('tr', null, h('td', null, f.properties.diameter != null ? f.properties.diameter : '—'),
          h('td', {class: 'num'}, fmt(f.properties.measured_length, 0)), h('td', {class: 'num'}, fmt(f.properties.max_length, 0)),
          h('td', null, usageBar(f.properties.usage || 0), fmt(100 * (f.properties.usage || 0), 0) + ' %'))))),
      rows.length > shown.length ? h('p', {class: 'small muted', style: {marginTop: '4px'}}, `Показаны 6 самых длинных из ${rows.length}; все части — слоем «Предельная длина» на карте.`) : null),
    {icon: 'ruler', count: over ? `превышено: ${over}` : 'в пределах', collapsible: true, open: false, id: 'length-card'});
  };

  const oksList = (v) => {
    const all = (v.oks || []).slice().sort((a, b) => natural(a.oks_id, b.oks_id));
    const unconnected = all.filter((o) => !o.connected).length;
    const list = onlyUnconnected ? all.filter((o) => !o.connected) : all;
    const shown = showAllOks ? list : list.slice(0, 8);
    return card(['Перспективные ', term('ОКС')], h('div', null,
      unconnected ? h('div', {class: 'row', style: {marginBottom: '6px'}},
        h('div', {class: 'seg sm'},
          h('button', {type: 'button', class: onlyUnconnected ? '' : 'on', dataset: {filter: 'all'}}, `Все ${all.length}`),
          h('button', {type: 'button', class: onlyUnconnected ? 'on' : '', dataset: {filter: 'unconnected'}}, `Не подключены ${unconnected}`))) : null,
      h('table', {class: 't compact'},
        h('thead', null, h('tr', null, h('th', null, 'ОКС'), h('th', {class: 'num', dataset: {tip: 'Расчётный расход, т/ч'}}, 'т/ч'), h('th', null, 'Присоединение'),
          h('th', {class: 'num'}, 'ДУ'), h('th', {class: 'num', dataset: {tip: 'Длина своей ветки — от разветвления или врезки до точки подключения; общие участки сюда не входят'}}, 'Ветка, м'), h('th', {class: 'num', dataset: {tip: 'Стоимость своей ветки, млн ₽; общие участки не входят'}}, 'Ветка, млн ₽'))),
        h('tbody', null, shown.map((o) => h('tr', {class: 'click', dataset: {oks: o.oks_id, key: o.oks_id}, tabindex: 0},
          h('td', {class: 'nowrap'}, h('span', {class: 'dot ' + (o.connected ? 'ok' : 'bad'), style: {marginRight: '6px'}}), o.oks_id),
          h('td', {class: 'num'}, fmt(o.flow_tph, 1)),
          h('td', {class: 'small'}, o.connected ? joinsShort(o) : h('span', {class: 'bad-text'}, o.reason_text || 'без маршрута')),
          h('td', {class: 'num'}, o.connected ? o.diameter : '—'),
          h('td', {class: 'num'}, o.connected ? fmt(o.own_length, 0) : '—'),
          h('td', {class: 'num'}, o.connected ? fmt(o.own_cost / 1e6, 1) : '—'))))),
      list.length > shown.length
        ? h('button', {type: 'button', class: 'quiet sm', dataset: {act: 'more-oks'}}, `Показать ещё ${list.length - shown.length}`, icon('chevron-down'))
        : null),
    {count: `${all.length - unconnected} из ${all.length} подключены`, icon: 'map-pin'});
  };

  // ---------------------------------------------------------------- the card of an OKS

  const oksCard = (run, v, oksId) => {
    const o = (v.oks || []).find((x) => x.oks_id === oksId);
    const back = h('button', {type: 'button', class: 'quiet sm', dataset: {back: '1'}, style: {marginBottom: '8px'}}, icon('arrow-left'), 'К варианту ' + v.variant_id);
    if (!o) return h('div', null, back, empty('ОКС не найден в этом варианте', oksId));
    if (!o.connected) {
      const penalty = 100e6 + 0.5e6 * (o.flow_tph || 0);
      return h('div', null, back,
        card([`ОКС ${o.oks_id}`, h('span', {class: 'badge bad', style: {marginLeft: '6px'}}, 'не подключён')], h('div', null,
          callout('bad', o.reason_text + (o.detail ? ` (${o.detail})` : '')),
          h('div', {class: 'kv'}, h('span', null, 'Расход'), h('span', null, fmt(o.flow_tph, 2) + ' т/ч'),
            h('span', null, 'Штраф'), h('span', null, rub(penalty, 1) + ' в стоимости варианта')),
          HINTS[o.reason] ? h('p', {class: 'small', style: {marginTop: '8px'}}, h('b', null, 'Рекомендация: '), HINTS[o.reason]) : null,
          h('div', {class: 'row', style: {marginTop: '8px'}},
            h('button', {type: 'button', class: 'primary', dataset: {path: o.oks_id}}, icon('map-pin'), 'На карте'),
            h('button', {type: 'button', dataset: {search: o.oks_id}}, icon('route'), 'Поиск трассы'),
            h('button', {type: 'button', dataset: {replayOks: o.oks_id}}, icon('play'), 'В построении'))), {icon: 'map-pin'}));
    }
    const data = store.variantData;
    const d = derived();
    const byId = new Map();
    if (data) for (const f of data.features) if (f.properties.id) byId.set(f.properties.id, f);
    const segs = o.path_segment_ids.map((id) => byId.get(id)).filter(Boolean);
    const chainSet = new Set(o.existing_chain);
    const flows = d.flows.filter((f) => chainSet.has(f.properties.existing_object_id));
    const recon = flows.filter((f) => f.properties.reconstructed);
    const ev = journal ? journal.events.filter((e) => (e.type === 'connect' || e.type === 'rebuild') && e.oks === oksId && e.route).pop() : null;
    return h('div', null, back,
      card([`ОКС ${o.oks_id}`, h('span', {class: 'badge ok', style: {marginLeft: '6px'}}, 'подключён')], h('div', null,
        h('p', {class: 'lead-sentence'}, `${joinsText(o)[0].toUpperCase()}${joinsText(o).slice(1)} · трасса ${metres(o.own_length)} · ДУ ${o.diameter} · поворотов ${o.turns}` +
          `${o.specials.length ? ' · спецпроход: ' + o.specials.map((x) => SPECIAL_TITLES[x] || x).join(', ') : ''}`),
        h('div', {class: 'kv'},
          h('span', null, 'Расход'), h('span', null, fmt(o.flow_tph, 2) + ' т/ч'),
          h('span', null, 'Стоимость трассы'), h('span', null, rub(o.own_cost, 2)),
          h('span', null, 'Путь от врезки'), h('span', null, metres(o.path_length)),
          h('span', null, 'Совместно с'), h('span', null, o.shared_with.length ? o.shared_with.join(', ') : '—')),
        h('div', {class: 'row', style: {marginTop: '8px'}},
          h('button', {type: 'button', class: 'primary', dataset: {path: o.oks_id}}, icon('map-pin'), 'На карте'),
          h('button', {type: 'button', dataset: {search: o.oks_id}}, icon('route'), 'Поиск трассы'),
          h('button', {type: 'button', dataset: {replayOks: o.oks_id}}, icon('play'), 'В построении'))), {icon: 'map-pin'}),
      card('Путь от источника', h('div', null,
        h('p', {class: 'small'}, `Существующая сеть: ${count(o.existing_chain.length, 'участок', 'участка', 'участков')} до врезки ${o.tie_object_id}. На них добавляется расход всех ОКС этой врезки` +
          (recon.length ? `; ${count(recon.length, 'участку не хватит', 'участкам не хватит', 'участкам не хватит')} пропускной способности.` : '; пропускной способности существующей сети хватает.')),
        recon.length ? h('table', {class: 't compact'}, h('thead', null, h('tr', null, h('th', null, 'Участок'), h('th', {class: 'num'}, 'Расход, т/ч'), h('th', {class: 'num'}, 'ДУ'))),
          h('tbody', null, recon.map((f) => h('tr', null, h('td', null, f.properties.existing_object_id),
            h('td', {class: 'num'}, `${fmt(f.properties.existing_flow_tph, 1)} → ${fmt(f.properties.calculated_flow_tph, 1)}`),
            h('td', {class: 'num'}, `${f.properties.existing_diameter} → ${f.properties.required_diameter}`))))) : null,
        h('h3', {style: {margin: '10px 0 4px'}}, 'Новые участки от врезки к ОКС'),
        h('table', {class: 't compact'}, h('thead', null, h('tr', null, h('th', null, 'Участок'), h('th', {class: 'num'}, 'ДУ'), h('th', {class: 'num'}, 'Расход, т/ч'), h('th', {class: 'num'}, 'Длина, м'), h('th', null, 'Прокладка'), h('th', {class: 'num'}, 'Предел'))),
          h('tbody', null, segs.map((f) => {
            const u = d.usageOf[f.properties.id];
            return h('tr', null, h('td', {class: 'small nowrap', title: f.properties.id}, f.properties.id.replace(/^v\d+-/, '')),
              h('td', {class: 'num'}, f.properties.diameter), h('td', {class: 'num'}, fmt(f.properties.flow_tph, 1)),
              h('td', {class: 'num'}, fmt(f.properties.length, 0)),
              h('td', {class: 'small'}, f.properties.laying_method === 'special' ? [term('спецпроход', 'Спецпроход'), ` ×${fmt(f.properties.k, 2)}`] : 'Обычная'),
              h('td', {class: 'small nowrap num'}, u ? fmt(100 * (u.usage || 0), 0) + ' %' : '—'));
          })))), {icon: 'route'}),
      card('Выбор места врезки', ev ? alternatives(ev) : h('p', {class: 'small muted'}, journal ? 'В журнале построения нет шага этого ОКС.' : 'Загрузка журнала…'), {icon: 'merge'}));
  };

  const alternatives = (ev) => {
    const alts = ev.alternatives || [];
    if (alts.length < 2) return h('p', {class: 'small'}, 'Другие места врезки в радиусе поиска недопустимы или заведомо дороже.');
    const best = Math.min(...alts.map((a) => a.score));
    const worst = Math.max(...alts.map((a) => a.score));
    return h('div', null,
      h('p', {class: 'small muted'}, 'Оценка каждого места — его вклад в ', term('оценка S', 'оценку S'), ': трасса плюс камера или врезка. Выбрано наименьшее.'),
      h('table', {class: 't compact'}, h('thead', null, h('tr', null, h('th', null, 'Место'), h('th', {class: 'num'}, 'S'), h('th', null, ''))),
        h('tbody', null, alts.map((a) => h('tr', {class: a.chosen ? 'sel' : ''},
          h('td', {class: 'small'}, a.chosen ? icon('check', 'sm') : null, ' ', (TARGET_TITLES[a.kind] || a.kind) + (a.object_id ? ' ' + a.object_id : '')),
          h('td', {class: 'num', dataset: a.estimate ? {tip: 'Не досчитывалось: даже прямая линия до этого места дороже выбранного'} : undefined},
            (a.estimate ? '≥ ' : '') + fmt(a.score, 2) + (a.score - best >= 0.005 ? ` (+${fmt(a.score - best, 2)})` : '')),
          h('td', {style: {width: '26%'}}, h('div', {class: 'alt-bar'}, h('span', {style: {width: Math.max(4, 100 * (1 - (a.score - best) / Math.max(1e-9, worst - best + 0.01))) + '%'}}))))))));
  };

  // ---------------------------------------------------------------- journal and map

  const ensureJournal = async () => {
    const r = store.route;
    if (!r.run || !store.run || store.run.status !== 'DONE') return;
    const key = r.run + '|' + (r.variant || '1');
    if (journalFor === key) return;
    journalFor = key;
    journal = null;
    try {
      journal = await loadJournal(r.run, r.variant || '1');
      if (journalFor === key) keepFocus(root, render);
    } catch (e) {
      journal = {events: []};
    }
  };

  const showPath = (oksId) => {
    const v = variantOf(store.run, store.route.variant);
    const o = v && (v.oks || []).find((x) => x.oks_id === oksId);
    if (!o || !store.variantData) return;
    const ids = new Set(o.connected ? o.path_segment_ids.concat([o.tie_in_id]) : []);
    const feats = store.variantData.features.filter((f) => (f.properties.id && ids.has(f.properties.id))
      || (!o.connected && f.properties.object_type === 'unconnected_oks' && f.properties.oks_id === oksId && f.geometry));
    if (feats.length) highlight({type: 'FeatureCollection', features: feats}, {maxZoom: o.connected ? 17 : 16});
  };

  on(root, 'click', '[data-act=more-oks]', () => { showAllOks = true; render(); });
  on(root, 'click', '[data-filter]', (e, t) => { onlyUnconnected = t.dataset.filter === 'unconnected'; render(); });
  on(root, 'click', '[data-act=unconnected]', () => { onlyUnconnected = true; showAllOks = true; render(); const el = root.querySelector('[data-filter]'); if (el) el.scrollIntoView({block: 'center'}); });
  on(root, 'click', '[data-variant]', (e, t) => go({variant: t.dataset.variant, oks: null}, {replace: false}));
  on(root, 'click', '[data-oks]', (e, t) => go({oks: t.dataset.oks}));
  on(root, 'keydown', '[data-oks]', (e, t) => { if (e.key === 'Enter') go({oks: t.dataset.oks}); });
  on(root, 'click', '[data-back]', () => { clearHighlight(); go({oks: null}); });
  on(root, 'click', '[data-path]', (e, t) => showPath(t.dataset.path));
  on(root, 'click', '[data-seg]', (e, t) => {
    const f = store.variantData && store.variantData.features.find((x) => x.properties.id === t.dataset.seg);
    if (f) highlight({type: 'FeatureCollection', features: [f]}, {maxZoom: 18});
  });
  on(root, 'click', '[data-act=compare]', () => go({step: 'compare', runA: store.route.run, variantA: store.route.variant || '1', runB: null, variantB: null}));
  on(root, 'click', '[data-act=export]', () => go({step: 'export', oks: null}));
  on(root, 'click', '[data-act=replay]', async () => {
    await ensureJournal();
    if (journal && journal.events.length) player.openBuild(journal.events, {title: `Построение · Вариант ${store.route.variant || '1'}`});
  });
  on(root, 'click', '[data-replay-oks]', async (e, t) => {
    await ensureJournal();
    if (journal && journal.events.length) player.openBuild(journal.events, {title: `Построение · ОКС ${t.dataset.replayOks}`, filterOks: t.dataset.replayOks});
  });
  on(root, 'click', '[data-search]', async (e, t) => {
    t.disabled = true;
    try {
      const trace = await api.search(store.route.run, t.dataset.search);
      player.openSearch(trace, {title: `Поиск трассы · ОКС ${t.dataset.search}`, subtitle: 'до существующей сети, без других ОКС варианта'});
    } catch (err) {
      toast('Не удалось: ' + err.message, true);
    } finally {
      t.disabled = false;
    }
  });
  const patchRun = async (patch) => {
    try {
      await api.updateRun(store.route.run, patch);
      await loadRun(store.route.run);
      loadRuns(store.route.dataset);
    } catch (err) { toast(err.message, true); }
  };
  on(root, 'click', '[data-act=rename]', async () => {
    const name = await promptDialog('Название расчёта', {value: store.run.name || ''});
    if (name != null) patchRun({name});
  });
  on(root, 'click', '[data-act=note]', async () => {
    const note = await promptDialog('Заметка к расчёту', {value: store.run.note || '', multiline: true, placeholder: 'Цель расчёта, что проверяем'});
    if (note != null) patchRun({note});
  });
  on(root, 'click', '[data-act=pin]', () => patchRun({pinned: !store.run.pinned}));

  subscribe('run', () => { if (store.route.step === 'result') { ensureJournal(); keepFocus(root, render); } });
  subscribe('variantData', () => { if (store.route.step === 'result') keepFocus(root, render); });

  return {
    update() {
      const r = store.route;
      if (r.run && (!store.run || store.run.id !== r.run)) loadRun(r.run).catch((e) => toast(e.message, true));
      ensureJournal();
      render();
      if (r.oks) setTimeout(() => showPath(r.oks), 300);
      root.scrollTop = 0;
    },
    leave() {
      clearHighlight();
    },
  };
}
