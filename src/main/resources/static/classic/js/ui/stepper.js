// The bar of steps: where the user is, what is done, what is in progress, what needs attention, what is not yet
// available (and why).

import {h, replace} from '../core/dom.js';
import {STEPS, stepOf} from '../core/router.js';
import {store} from '../core/store.js';
import {icon} from './components.js';

/** State of every step from what is loaded: {id: {state, enabled, why}}. */
export function stepStates() {
  const r = store.route;
  const ds = r.dataset ? store.overview : null;
  const ready = ds && ds.id === r.dataset && ds.status === 'READY';
  const blocking = ready && (ds.readiness || []).some((c) => c.blocking);
  const problems = ready && (ds.issues || []).some((i) => i.group === 'problem');
  const runs = r.dataset ? store.runs || [] : [];
  const done = runs.filter((x) => x.status === 'DONE');
  const active = runs.some((x) => x.status === 'RUNNING' || x.status === 'QUEUED');
  const run = store.run && store.run.id === (r.run || (done[0] && done[0].id)) ? store.run : null;
  const best = run && run.summary ? run.summary.slice().sort((a, b) => a.rank - b.rank)[0] : null;
  const attention = run && ((run.validation && run.validation.errors > 0) || (best && best.unconnected && best.unconnected.length));
  const noDs = 'Сначала выберите или загрузите набор';
  return {
    data: {state: r.dataset ? 'done' : 'current', enabled: true},
    check: {state: !r.dataset ? 'todo' : ds && ds.status !== 'READY' ? (ds.status === 'FAILED' ? 'attention' : 'working') : blocking || problems ? 'attention' : ready ? 'done' : 'todo',
      enabled: !!r.dataset, why: noDs},
    calc: {state: active ? 'working' : done.length ? 'done' : 'todo', enabled: !!ready && !blocking,
      why: !r.dataset ? noDs : blocking ? 'Набор не готов к расчёту — см. проверку данных' : 'Набор ещё импортируется'},
    result: {state: attention ? 'attention' : done.length ? 'done' : 'todo', enabled: done.length > 0 || !!r.run, why: 'Нет завершённого расчёта'},
  };
}

export function renderStepper(el, onPick) {
  const st = stepStates();
  const nodes = [];
  const currentStep = stepOf(store.route.step);
  STEPS.forEach((s, i) => {
    const x = st[s.id];
    const current = currentStep === s.id;
    const cls = ['step-tab', current ? 'current' : '', x.state].join(' ');
    const mark = x.state === 'done' && !current ? icon('check') : x.state === 'attention' && !current ? icon('alert')
      : x.state === 'working' && !current ? icon('loader', 'spin') : String(s.n);
    nodes.push(h('button', {type: 'button', class: cls, disabled: !x.enabled && !current, dataset: {step: s.id, tip: x.enabled ? '' : x.why},
      'aria-current': current ? 'step' : null, onclick: () => onPick(s.id)},
    h('span', {class: 'n'}, mark),
    h('span', {class: 'lbl'}, s.title)));
    if (i < STEPS.length - 1) nodes.push(h('span', {class: 'step-sep', 'aria-hidden': 'true'}, icon('chevron-right')));
  });
  replace(el, nodes);
}
