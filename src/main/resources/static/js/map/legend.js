// Legend of the map — the panel of layers in groups: input data, new network, existing network, notes, analysis
// overlays (construction, search, comparison), basemap.

import {h, on, replace} from '../core/dom.js';
import {prefs} from '../core/store.js';
import {DN_STOPS, RESTRICTION_COLORS, groups, isVisible, onLegendChange, setBasemap, setVisible, showFlowAfter} from './map.js';
import {icon} from '../ui/components.js';

const SECTIONS = [
  ['before', 'Исходные данные'],
  ['after', 'Новая сеть'],
  ['change', 'Существующая сеть'],
  ['problems', 'Замечания'],
  ['overlay', 'Слои анализа'],
];

let restrictionTitles = {};
let flowAfter = true;

export function setLegendRestrictions(titles) {
  restrictionTitles = titles;
  render();
}

const el = () => document.getElementById('legend');

function swatch(g) {
  const cls = g.kind === 'line' ? 'sw line' : g.kind === 'dot' ? 'sw dot' : 'sw';
  return h('span', {class: cls, style: {background: g.sw, border: g.stroke ? `2px solid ${g.stroke}` : ''}});
}

function render() {
  const root = el();
  const stored = prefs.get('legendCollapsed', null);
  const collapsed = stored == null ? window.innerWidth < 900 : stored;
  root.classList.toggle('collapsed', collapsed);
  const body = h('div', {class: 'lb'});
  for (const [sec, title] of SECTIONS) {
    const items = Object.entries(groups).filter(([, g]) => (g.section || 'overlay') === sec);
    if (!items.length) continue;
    body.append(h('div', {class: 'grp'}, title));
    for (const [id, g] of items) {
      if (g.display) {
        // a legend-only row: a colour of a layer that is switched together with another row
        body.append(h('div', {class: 'li', style: {cursor: 'default', paddingLeft: '33px'}}, swatch(g), h('span', {class: 'txt'}, g.title)));
        continue;
      }
      body.append(h('label', {class: 'li'},
        h('input', {type: 'checkbox', checked: isVisible(id), dataset: {group: id}}),
        swatch(g),
        h('span', {class: 'txt'}, g.title, g.hint ? h('span', {class: 'hint'}, g.hint) : null)));
      if (g.ramp) {
        body.append(h('div', {class: 'sub'},
          h('div', {class: 'ramp'}, DN_STOPS.filter((x, i) => i % 2 === 1).map((c) => h('span', {style: {background: c}}))),
          h('div', {class: 'ramp-labels'}, DN_STOPS.filter((x, i) => i % 2 === 0).map((d) => h('span', null, String(d))))));
      }
      if (g.restrictions) {
        body.append(h('div', {class: 'chips sub'},
          Object.entries(restrictionTitles).filter(([k]) => RESTRICTION_COLORS[k] && !['heat_network', 'oks', 'oks_existing', 'building'].includes(k))
            .map(([k, v]) => h('span', {class: 'chip', style: {background: RESTRICTION_COLORS[k] + '66', borderColor: RESTRICTION_COLORS[k]}}, v))));
      }
      if (g.flowToggle) {
        body.append(h('div', {class: 'row sub'},
          h('span', {class: 'small muted'}, 'Расход:'),
          h('span', {class: 'seg sm'},
            h('button', {type: 'button', class: flowAfter ? '' : 'on', dataset: {flow: 'before'}}, 'До'),
            h('button', {type: 'button', class: flowAfter ? 'on' : '', dataset: {flow: 'after'}}, 'После'))));
      }
      if (g.legendExtra) body.append(g.legendExtra());
    }
  }
  body.append(h('div', {class: 'grp'}, 'Подложка'),
    h('label', {class: 'li'}, h('input', {type: 'checkbox', checked: prefs.get('osm', false), dataset: {osm: '1'}}),
      h('span', {class: 'sw', style: {background: 'var(--line-2)'}}), h('span', {class: 'txt'}, 'OpenStreetMap', h('span', {class: 'hint'}, 'требуется интернет'))));
  replace(root,
    h('div', {class: 'lh'}, icon('layers'), collapsed ? null : 'Слои',
      h('button', {type: 'button', class: 'icon sm', dataset: {toggle: '1'}, title: collapsed ? 'Показать слои' : 'Свернуть', 'aria-label': collapsed ? 'Показать слои' : 'Свернуть'},
        icon(collapsed ? 'chevron-left' : 'chevron-right'))),
    body);
}

export function installLegend() {
  const root = el();
  on(root, 'change', 'input[data-group]', (e, t) => setVisible(t.dataset.group, t.checked));
  on(root, 'change', 'input[data-osm]', (e, t) => setBasemap(t.checked));
  on(root, 'click', '[data-toggle]', () => {
    prefs.set('legendCollapsed', !root.classList.contains('collapsed'));
    render();
  });
  on(root, 'click', '[data-flow]', (e, t) => {
    flowAfter = t.dataset.flow === 'after';
    showFlowAfter(flowAfter);
    render();
  });
  onLegendChange(render);
  render();
}
