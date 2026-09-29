// Numbers, sizes, dates and words in Russian.

const nf = (d) => new Intl.NumberFormat('ru-RU', {minimumFractionDigits: d, maximumFractionDigits: d});
const cache = {};

export function fmt(x, d = 0) {
  if (x == null || Number.isNaN(Number(x))) return '—';
  return (cache[d] || (cache[d] = nf(d))).format(Number(x));
}

export const metres = (x) => (x == null ? '—' : x >= 10000 ? fmt(x / 1000, 1) + ' км' : fmt(x, 0) + ' м');

export function bytes(b) {
  if (b == null) return '—';
  if (b > 1 << 30) return fmt(b / (1 << 30), 2) + ' ГБ';
  if (b > 1 << 20) return fmt(b / (1 << 20), 1) + ' МБ';
  return fmt(Math.max(1, b / 1024), 0) + ' КБ';
}

export function when(t) {
  if (!t) return '';
  return new Date(t).toLocaleString('ru-RU', {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'});
}

/** plural(3, 'врезка', 'врезки', 'врезок') → 'врезки'. */
export function plural(n, one, few, many) {
  const m10 = Math.abs(n) % 10;
  const m100 = Math.abs(n) % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
  return many;
}

export const count = (n, one, few, many) => `${fmt(n)} ${plural(n, one, few, many)}`;

export const STATUS_RU = {
  UPLOADED: 'В очереди', IMPORTING: 'Импорт', READY: 'Готов', FAILED: 'Ошибка', CANCELLED: 'Отменён',
  QUEUED: 'В очереди', RUNNING: 'Считается', DONE: 'Готов',
};

/** Roubles as millions with the rouble sign — for tables and tiles. */
export const rub = (x, d = 1) => (x == null ? '—' : fmt(x / 1e6, d) + ' млн ₽');

/** Natural order of identifiers: oks-2 before oks-10, "3" before "12". */
export const natural = (a, b) => String(a).localeCompare(String(b), 'ru', {numeric: true, sensitivity: 'base'});

export const ACTIVE = ['UPLOADED', 'IMPORTING', 'QUEUED', 'RUNNING'];
