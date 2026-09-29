// Shared state of the page with change notifications. What is on the server stays on the server:
// here are only the loaded copies and the current selection (which also lives in the address).

const listeners = new Map();

export const store = {
  route: {step: 'data'},
  catalog: null,          // /api/params
  datasets: [],           // /api/datasets
  overview: null,         // /api/datasets/{id}/overview of the current dataset
  runs: [],               // runs of the current dataset
  run: null,              // /api/runs/{id} of the current run
  variantData: null,      // GeoJSON of the current variant
  journals: {},           // "run|variant" → journal

  set(key, value) {
    // Опрос сервера кладёт сюда одни и те же ответы раз в секунду. Если ничего не изменилось, слушателей не
    // трогаем: иначе панель пересобирается целиком — теряются прокрутка, раскрытые блоки и выделение текста.
    if (same(this[key], value)) {
      this[key] = value;
      return;
    }
    this[key] = value;
    emit(key, value);
  },
};

/** Дёшево сравниваем то, что приходит с сервера: это разобранный JSON без функций и циклов. */
function same(a, b) {
  if (a === b) return true;
  if (a == null || b == null || typeof a !== 'object' || typeof b !== 'object') return false;
  try {
    return JSON.stringify(a) === JSON.stringify(b);
  } catch (e) {
    return false;
  }
}

export function subscribe(key, fn) {
  if (!listeners.has(key)) listeners.set(key, new Set());
  listeners.get(key).add(fn);
  return () => listeners.get(key).delete(fn);
}

export function emit(key, value) {
  const l = listeners.get(key);
  if (l) for (const fn of [...l]) {
    try { fn(value); } catch (e) { console.error(e); }
  }
}

/** Per-viewer conveniences in the browser (collapsed panels, speed); never state that matters. */
export const prefs = {
  get(key, def) {
    try {
      const v = localStorage.getItem('heatnet.' + key);
      return v == null ? def : JSON.parse(v);
    } catch (e) { return def; }
  },
  set(key, value) {
    try { localStorage.setItem('heatnet.' + key, JSON.stringify(value)); } catch (e) { /* storage off */ }
  },
};
