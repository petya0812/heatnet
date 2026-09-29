// What is animated and how fast: one menu for all animations. The system setting "reduce motion" turns
// animations off by default.

import {h, replace} from '../core/dom.js';
import {emit, prefs} from '../core/store.js';

const reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

const DEFAULTS = {
  build: !reduced,        // replay of the construction (while calculating and on request)
  live: !reduced,         // follow the construction while the calculation runs
  transitions: !reduced,  // switching variants
  flow: false,            // movement of the flow along the new network
  speed: 1,               // replay speed
  transitionMs: 700,      // switching variants, ms
};

export const anim = Object.assign({}, DEFAULTS, prefs.get('anim', {}));

export function setAnim(key, value) {
  anim[key] = value;
  prefs.set('anim', anim);
  emit('anim', anim);
}

export function installMenu(button, menu) {
  const render = () => {
    replace(menu,
      h('h4', null, 'Настройки показа'),
      reduced ? h('p', {class: 'small muted'}, 'В системе включено «уменьшить движение» — показ построения по умолчанию выключен.') : null,
      opt('build', 'Проигрывать построение'),
      opt('live', 'Показывать построение во время расчёта'),
      opt('transitions', 'Плавный переход между вариантами'),
      opt('flow', 'Анимация потока по новой сети'),
      h('div', {class: 'opt'}, h('span', null, 'Скорость'),
        h('select', {onchange: (e) => setAnim('speed', Number(e.target.value))},
          [0.5, 1, 2, 4, 8].map((s) => h('option', {value: s, selected: anim.speed === s}, s + '×')))),
      h('div', {class: 'opt'}, h('span', null, 'Длительность перехода, мс'),
        h('select', {onchange: (e) => setAnim('transitionMs', Number(e.target.value))},
          [300, 700, 1200, 2000].map((s) => h('option', {value: s, selected: anim.transitionMs === s}, String(s))))),
      h('div', {class: 'row', style: {marginTop: '8px'}}, h('button', {type: 'button', onclick: () => {
        Object.assign(anim, DEFAULTS);
        prefs.set('anim', anim);
        emit('anim', anim);
        render();
      }}, 'По умолчанию'), h('span', {class: 'spacer'}), h('button', {type: 'button', onclick: () => { menu.hidden = true; }}, 'Закрыть')));
  };
  const opt = (key, label) => h('label', {class: 'opt check'},
    h('input', {type: 'checkbox', checked: !!anim[key], onchange: (e) => setAnim(key, e.target.checked)}), label);
  button.addEventListener('click', (e) => {
    e.stopPropagation();
    render();
    const r = button.getBoundingClientRect();
    menu.style.top = r.bottom + 6 + 'px';
    menu.style.left = Math.max(8, r.right - 320) + 'px';
    menu.hidden = !menu.hidden;
  });
  document.addEventListener('click', (e) => {
    if (!menu.hidden && !menu.contains(e.target) && e.target !== button) menu.hidden = true;
  });
}
