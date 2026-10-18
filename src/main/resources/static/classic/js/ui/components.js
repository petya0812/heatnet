// Shared building blocks: icons, badges, status, progress, empty states, cards, tabs, toasts, dialogs.

import {h, replace} from '../core/dom.js';
import {STATUS_RU, fmt} from '../core/format.js';

export const CLASS_TITLES = {
  norm: 'Правило',
  interpretation: 'Уточнение',
  data_assumption: 'Восполнено',
  algorithm: 'Настройка',
};

const CLASS_TIPS = {
  norm: 'Правило технических требований. Не меняется.',
  interpretation: 'Как сервис применяет правило, которое можно прочитать по-разному.',
  data_assumption: 'Значения нет во входных данных — сервис восполнил его по правилу.',
  algorithm: 'Настройка трассировки: может только ужесточить правила.',
};

/** Whether a parameter that depends on another one is shown with these values. */
export function paramVisible(e, values) {
  const d = e.depends_on;
  if (!d) return true;
  const v = String(values[d.key]);
  return d.not != null ? v !== String(d.not) : v === String(d.value);
}

export function classBadge(cls, title) {
  return h('span', {class: 'badge ' + cls, dataset: {tip: CLASS_TIPS[cls] || ''}}, title || CLASS_TITLES[cls] || cls);
}

/** Знак из спрайта static/icons.svg; class — дополнительные классы (sm, lg, spin). */
export function icon(name, cls) {
  const el = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  el.setAttribute('class', 'icon' + (cls ? ' ' + cls : ''));
  el.setAttribute('aria-hidden', 'true');
  el.setAttribute('focusable', 'false');
  const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  use.setAttribute('href', 'icons.svg#i-' + name);
  el.appendChild(use);
  return el;
}

/** Кнопка-знак с подсказкой (для повторяющихся действий в строках списка). */
export function iconButton(name, title, attrs = {}) {
  const {class: extra, ...rest} = attrs;
  return h('button', Object.assign({type: 'button', class: 'icon' + (extra ? ' ' + extra : ''), title, 'aria-label': title}, rest), icon(name));
}

const STATUS_ICON = {READY: 'check', DONE: 'check', FAILED: 'x', CANCELLED: 'x', RUNNING: 'loader', IMPORTING: 'loader', QUEUED: 'loader', UPLOADED: 'loader'};

export const status = (s) => h('span', {class: 'status ' + s},
  icon(STATUS_ICON[s] || 'info', STATUS_ICON[s] === 'loader' ? 'spin' : ''), STATUS_RU[s] || s);

export function progress(done, total, label) {
  return h('div', {class: 'progress' + (total ? '' : ' indeterminate')},
    h('div', {style: {width: total ? Math.min(100, 100 * done / total) + '%' : '30%'}}),
    h('span', null, label || (total ? `${fmt(done)} из ${fmt(total)}` : '')));
}

export function empty(title, text, ...actions) {
  return h('div', {class: 'empty'}, icon('info', 'lg'), h('strong', null, title), text ? h('div', null, text) : null,
    actions.length ? h('div', {class: 'row', style: {justifyContent: 'center', marginTop: '10px'}}, actions) : null);
}

/**
 * Карточка раздела: заголовок со знаком и счётчиком справа. collapsible — сворачиваемая (details),
 * open — раскрыта, id — для запоминания состояния.
 */
export function card(title, body, {count, open, collapsible, id, icon: ic, cls} = {}) {
  const head = [ic ? icon(ic) : null, title, count != null && count !== '' ? h('span', {class: 'count'}, count) : null];
  if (collapsible) {
    return h('details', {class: 'card' + (cls ? ' ' + cls : ''), open: !!open, id},
      h('summary', null, head, h('span', {class: 'chev'}, icon('chevron-right'))),
      body);
  }
  return h('section', {class: 'card' + (cls ? ' ' + cls : ''), id}, title ? h('h3', null, head) : null, body);
}

/** Вкладки: items — [{id, title, icon, disabled, tip}], current — id, onPick(id). */
export function tabs(items, current, onPick, cls) {
  return h('div', {class: 'tabs' + (cls ? ' ' + cls : ''), role: 'tablist'}, items.map((t) =>
    h('button', {type: 'button', role: 'tab', class: 'tab' + (t.id === current ? ' on' : ''), 'aria-selected': String(t.id === current),
      disabled: !!t.disabled, dataset: t.tip ? {tip: t.tip} : undefined, onclick: () => onPick(t.id)},
    t.icon ? icon(t.icon) : null, t.title)));
}

/** Знак состояния: ok / warn / bad / info / neutral. */
export function mark(kind) {
  const names = {ok: 'check', warn: 'alert', bad: 'x', info: 'info', neutral: 'circle-dot'};
  return h('span', {class: 'mark ' + kind}, icon(names[kind] || 'info'));
}

export function callout(kind, text, extra) {
  const names = {ok: 'check-circle', warn: 'alert', bad: 'alert-circle', info: 'info'};
  return h('div', {class: 'callout ' + (kind || 'info')}, icon(names[kind] || 'info'), h('div', {class: 'grow'}, text, extra));
}

export function toast(text, bad) {
  const t = h('div', {class: 'toast' + (bad ? ' bad' : ''), role: 'status'}, icon(bad ? 'alert-circle' : 'check-circle'), text);
  document.getElementById('toasts').appendChild(t);
  setTimeout(() => t.remove(), bad ? 6000 : 3500);
}

export function dialog(title, content, {narrow, footer} = {}) {
  const d = document.getElementById('dialog');
  d.className = 'dialog' + (narrow ? ' narrow' : '');
  replace(d,
    h('div', {class: 'dh'}, h('h2', null, title), iconButton('x', 'Закрыть', {onclick: () => d.close()})),
    h('div', {class: 'db'}, content),
    footer ? h('div', {class: 'df'}, footer) : null);
  d.showModal();
  return d;
}

/** Диалог с одним текстовым полем; резолвится строкой или null. */
export function promptDialog(title, {label, value = '', placeholder, multiline, okLabel = 'Сохранить'} = {}) {
  return new Promise((resolve) => {
    let done = false;
    const input = multiline
      ? h('textarea', {placeholder, 'aria-label': label || title}, value)
      : h('input', {type: 'text', value, placeholder, 'aria-label': label || title});
    const finish = (v) => { if (done) return; done = true; d.close(); resolve(v); };
    const d = dialog(title,
      h('form', {class: 'field', onsubmit: (e) => { e.preventDefault(); finish(input.value); }},
        label ? h('span', {class: 'label'}, label) : null, input),
      {narrow: true, footer: [
        h('button', {type: 'button', onclick: () => finish(null)}, 'Отмена'),
        h('button', {type: 'button', class: 'primary', onclick: () => finish(input.value)}, okLabel)]});
    d.addEventListener('close', () => finish(null), {once: true});
    setTimeout(() => input.focus(), 30);
  });
}

/** Диалог подтверждения; резолвится true/false. */
export function confirmDialog(title, text, {okLabel = 'Удалить', danger = true} = {}) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => { if (done) return; done = true; d.close(); resolve(v); };
    const d = dialog(title, h('p', null, text), {narrow: true, footer: [
      h('button', {type: 'button', onclick: () => finish(false)}, 'Отмена'),
      h('button', {type: 'button', class: 'primary' + (danger ? ' danger' : ''), onclick: () => finish(true)}, okLabel)]});
    d.addEventListener('close', () => finish(false), {once: true});
  });
}

export function metric(value, label, tip, cls) {
  return h('div', {class: 'metric' + (cls ? ' ' + cls : ''), dataset: tip ? {tip} : undefined}, h('div', {class: 'v'}, value), h('div', {class: 'l'}, label));
}

/** Полоска использования (0…1): зелёная до 80 %, жёлтая до 100 %, красная выше. */
export function usageBar(share) {
  const cls = share > 1 ? ' bad' : share >= 0.8 ? ' warn' : '';
  return h('span', {class: 'usage' + cls}, h('span', {style: {width: Math.min(100, 100 * share) + '%'}}));
}
