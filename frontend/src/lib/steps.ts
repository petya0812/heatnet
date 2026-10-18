// State of every step from what is loaded: where the user is, what is done, what is in progress, what needs
// attention, what is not yet available (and why).

import {computed} from 'vue';
import {useApp} from '@/stores/app';

export const STEPS = [
  {id: 'data', n: 1, title: 'Данные', long: 'Исходные данные'},
  {id: 'check', n: 2, title: 'Проверка', long: 'Проверка данных'},
  {id: 'calc', n: 3, title: 'Расчёт', long: 'Расчёт'},
  {id: 'result', n: 4, title: 'Результат', long: 'Результат'},
] as const;

export type StepState = 'todo' | 'current' | 'done' | 'working' | 'attention';

export function useStepStates() {
  const app = useApp();
  return computed(() => {
    const ds = app.currentOverview;
    const has = !!app.datasetId;
    const ready = !!ds && ds.status === 'READY';
    const blocking = ready && (ds.readiness || []).some((c: any) => c.blocking);
    const problems = ready && (ds.issues || []).some((i: any) => i.group === 'problem');
    const runs = has && app.runsFor === app.datasetId ? app.runs : [];
    const done = runs.filter((x) => x.status === 'DONE');
    const active = runs.some((x) => x.status === 'RUNNING' || x.status === 'QUEUED');
    const run = app.run && app.run.id === (app.runId || (done[0] && done[0].id)) ? app.run : null;
    const best = run && run.summary ? run.summary.slice().sort((a: any, b: any) => a.rank - b.rank)[0] : null;
    const attention = !!run && ((run.validation && run.validation.errors > 0) || (best && best.unconnected && best.unconnected.length));
    const noDs = 'Сначала выберите или загрузите набор';
    return {
      data: {state: (has ? 'done' : 'todo') as StepState, enabled: true, why: ''},
      check: {
        state: (!has ? 'todo' : ds && ds.status !== 'READY' ? (ds.status === 'FAILED' ? 'attention' : 'working') : blocking || problems ? 'attention' : ready ? 'done' : 'todo') as StepState,
        enabled: has, why: noDs,
      },
      calc: {
        state: (active ? 'working' : done.length ? 'done' : 'todo') as StepState, enabled: ready && !blocking,
        why: !has ? noDs : blocking ? 'Набор не готов к расчёту — см. проверку данных' : 'Набор ещё импортируется',
      },
      result: {state: (attention ? 'attention' : done.length ? 'done' : 'todo') as StepState, enabled: done.length > 0 || !!app.runId, why: 'Пока нет завершённого расчёта'},
    };
  });
}
