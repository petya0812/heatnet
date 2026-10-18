/** Per-viewer conveniences in the browser (collapsed sections, speed, theme); never state that matters. */
export const prefs = {
  get<T>(key: string, def: T): T {
    try {
      const v = localStorage.getItem('heatnet.' + key);
      return v == null ? def : JSON.parse(v);
    } catch (e) { return def; }
  },
  set(key: string, value: unknown) {
    try { localStorage.setItem('heatnet.' + key, JSON.stringify(value)); } catch (e) { /* storage off */ }
  },
};
