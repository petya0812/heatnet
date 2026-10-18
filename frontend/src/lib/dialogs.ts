// Confirmations and short inputs in a dialog of the page, never window.confirm / prompt.

import {reactive} from 'vue';

export const dialogState = reactive({
  open: false,
  kind: 'confirm' as 'confirm' | 'prompt',
  title: '',
  text: '',
  ok: 'Да',
  danger: false,
  value: '',
  multiline: false,
  placeholder: '',
  resolve: null as null | ((v: any) => void),
});

export function confirmDialog(title: string, text: string, {ok = 'Удалить', danger = true} = {}): Promise<boolean> {
  return new Promise((resolve) => {
    Object.assign(dialogState, {open: true, kind: 'confirm', title, text, ok, danger, resolve});
  });
}

export function promptDialog(title: string, {value = '', multiline = false, placeholder = '', ok = 'Сохранить'} = {}): Promise<string | null> {
  return new Promise((resolve) => {
    Object.assign(dialogState, {open: true, kind: 'prompt', title, text: '', ok, danger: false, value, multiline, placeholder, resolve});
  });
}

export function closeDialog(result: unknown) {
  const r = dialogState.resolve;
  dialogState.open = false;
  dialogState.resolve = null;
  if (r) r(result);
}
