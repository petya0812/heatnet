// Short notices at the bottom of the screen; an error stays longer and may carry one action.

import {reactive} from 'vue';

export interface Toast {
  id: number;
  text: string;
  tone: 'neutral' | 'ok' | 'bad';
  action?: {label: string; run: () => void};
}

export const toasts = reactive<Toast[]>([]);
let seq = 0;

export function toast(text: string, opts: {tone?: Toast['tone']; action?: Toast['action']; ms?: number} | boolean = {}) {
  const o = typeof opts === 'boolean' ? {tone: opts ? 'bad' as const : 'neutral' as const} : opts;
  const t: Toast = {id: ++seq, text, tone: o.tone || 'neutral', action: o.action};
  toasts.push(t);
  if (toasts.length > 4) toasts.splice(0, toasts.length - 4);
  setTimeout(() => dismiss(t.id), o.ms || (t.tone === 'bad' ? 7000 : t.action ? 8000 : 4000));
}

export function dismiss(id: number) {
  const i = toasts.findIndex((x) => x.id === id);
  if (i >= 0) toasts.splice(i, 1);
}
