// Light, dark or as the system says; the map base follows the panels.

import {ref, watch} from 'vue';
import {prefs} from './prefs';
import {setTheme} from '@/map/engine';

export type ThemeMode = 'light' | 'dark' | 'system';
export const themeMode = ref<ThemeMode>(prefs.get('theme', 'system'));
const media = window.matchMedia('(prefers-color-scheme: dark)');

export const resolved = () => (themeMode.value === 'system' ? (media.matches ? 'dark' : 'light') : themeMode.value);

function apply() {
  const t = resolved();
  document.documentElement.classList.toggle('dark', t === 'dark');
  setTheme(t);
}

export function installTheme() {
  apply();
  media.addEventListener('change', apply);
  watch(themeMode, (m) => {
    prefs.set('theme', m);
    apply();
  });
}
