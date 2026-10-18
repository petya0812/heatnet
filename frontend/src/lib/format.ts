// Numbers, sizes, dates and words in Russian.

const cache: Record<number, Intl.NumberFormat> = {};
const nf = (d: number) => (cache[d] ||= new Intl.NumberFormat('ru-RU', {minimumFractionDigits: d, maximumFractionDigits: d}));

export function fmt(x: unknown, d = 0): string {
  if (x == null || Number.isNaN(Number(x))) return '—';
  return nf(d).format(Number(x));
}

export const metres = (x: number | null | undefined) => (x == null ? '—' : x >= 10000 ? fmt(x / 1000, 1) + ' км' : fmt(x, 0) + ' м');

export function bytes(b: number | null | undefined) {
  if (b == null || b < 0) return '—';
  if (b > 1 << 30) return fmt(b / (1 << 30), 2) + ' ГБ';
  if (b > 1 << 20) return fmt(b / (1 << 20), 1) + ' МБ';
  return fmt(Math.max(1, b / 1024), 0) + ' КБ';
}

export function when(t: string | null | undefined) {
  if (!t) return '';
  return new Date(t).toLocaleString('ru-RU', {day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit'});
}

/** «5 минут назад», «вчера в 14:20» — for lists where the order matters more than the exact time. */
export function ago(t: string | null | undefined) {
  if (!t) return '';
  const d = new Date(t);
  const s = (Date.now() - d.getTime()) / 1000;
  if (s < 60) return 'только что';
  if (s < 3600) return `${Math.round(s / 60)} мин назад`;
  const today = new Date();
  const time = d.toLocaleTimeString('ru-RU', {hour: '2-digit', minute: '2-digit'});
  if (d.toDateString() === today.toDateString()) return `сегодня в ${time}`;
  const y = new Date(today.getTime() - 86400000);
  if (d.toDateString() === y.toDateString()) return `вчера в ${time}`;
  return when(t);
}

/** plural(3, 'врезка', 'врезки', 'врезок') → 'врезки'. */
export function plural(n: number, one: string, few: string, many: string) {
  const m10 = Math.abs(n) % 10;
  const m100 = Math.abs(n) % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
  return many;
}

export const count = (n: number, one: string, few: string, many: string) => `${fmt(n)} ${plural(n, one, few, many)}`;

export const STATUS_RU: Record<string, string> = {
  UPLOADED: 'В очереди', IMPORTING: 'Импорт', READY: 'Готов', FAILED: 'Ошибка', CANCELLED: 'Отменён',
  QUEUED: 'В очереди', RUNNING: 'Считается', DONE: 'Готов',
};

/** Roubles as millions — for tables and tiles. */
export const mln = (x: number | null | undefined, d = 1) => (x == null ? '—' : fmt(x / 1e6, d));
export const rub = (x: number | null | undefined, d = 1) => (x == null ? '—' : fmt(x / 1e6, d) + ' млн ₽');

/** Natural order of identifiers: oks-2 before oks-10. */
export const natural = (a: unknown, b: unknown) => String(a).localeCompare(String(b), 'ru', {numeric: true, sensitivity: 'base'});

export const ACTIVE = ['UPLOADED', 'IMPORTING', 'QUEUED', 'RUNNING'];
export const isActive = (s: string | undefined) => !!s && ACTIVE.includes(s);

/**
 * The name of a dataset is the name of its file; the seeding script (tools/seed_datasets.py) puts the group in
 * front of a middle dot: «Проект · ЗИЛ». Everything else is «Загруженные».
 */
export function splitName(name: string | null | undefined) {
  const m = /^(Проект|Пример|Нагрузка)\s·\s(.+)$/.exec(name || '');
  return m ? {group: m[1], title: m[2]} : {group: 'Загруженные', title: name || ''};
}

export const GROUPS = [
  {key: 'Проект', title: 'Проекты'},
  {key: 'Загруженные', title: 'Загруженные'},
  {key: 'Пример', title: 'Примеры'},
  {key: 'Нагрузка', title: 'Большие наборы'},
];

/** A sentence from the server may start in lower case: «выход закрыт» → «Выход закрыт». */
export const cap = (t: string | null | undefined) => (t ? t[0].toUpperCase() + t.slice(1) : '');
