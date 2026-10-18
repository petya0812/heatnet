// The catalogue of parameters (GET /api/params): what is shown where and how a value reads in words.

import {fmt} from './format';

export function paramVisible(e: any, values: Record<string, unknown>) {
  const d = e.depends_on;
  if (!d) return true;
  const v = String(values[d.key]);
  return d.not != null ? v !== String(d.not) : v === String(d.value);
}

export const CLASS_TITLES: Record<string, string> = {
  algorithm: 'Настройка трассировки', data_assumption: 'Недостающие данные', norm: 'Технические требования', interpretation: 'Как применяются правила',
};

/** «Отступ: 2 м», «Углы поворота: только 90°» — a changed parameter in a few words. */
export function paramLabel(e: any, value: unknown) {
  const o = (e.options || []).find((x: any) => x.value === String(value));
  if (o) return o.label;
  const n = Number(value);
  if (e.unit === 'доля') return fmt(n * 100) + ' %';
  return fmt(n, Number.isInteger(n) ? 0 : 2) + (e.unit === '°' ? '°' : e.unit ? ' ' + e.unit : '');
}

export function paramDiff(catalog: any, params: Record<string, unknown> | null) {
  if (!params || !catalog) return [];
  const out: {key: string; title: string; value: string}[] = [];
  for (const e of catalog.params) {
    if (!e.editable || params[e.key] == null || String(params[e.key]) === String(e.default)) continue;
    if (!paramVisible(e, params)) continue;
    out.push({key: e.key, title: e.title, value: paramLabel(e, params[e.key])});
  }
  return out;
}
