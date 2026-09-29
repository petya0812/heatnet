// Small DOM helpers: element builder, escaping, event delegation.

export const esc = (s) => String(s == null ? '' : s)
  .replace(/[&<>"']/g, (c) => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

/**
 * h('div', {class: 'x', onclick: fn, dataset: {id: 1}}, child, 'text', [more]) — children may be nodes, strings,
 * arrays, null. A string child is text.
 */
export function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k.startsWith('on') && typeof v === 'function') {
        el.addEventListener(k.slice(2), v);
        (el.__on = el.__on || []).push([k.slice(2), v]);   // запоминаем, чтобы перенести при обновлении на месте
      }
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
      else if (k === 'html') el.innerHTML = v;
      else if (k in el && k !== 'list' && k !== 'form' && typeof v !== 'string') el[k] = v;
      else el.setAttribute(k, v === true ? '' : v);
    }
  }
  append(el, children);
  return el;
}

function append(el, children) {
  for (const c of children) {
    if (c == null || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else if (c instanceof Node) el.appendChild(c);
    else el.appendChild(document.createTextNode(String(c)));
  }
}

/**
 * Updates a region in place: nodes are patched, not replaced, so scroll, focus, caret, selection and open blocks
 * survive.
 */
export function replace(el, ...children) {
  const next = el.ownerDocument.createElement(el.tagName);
  append(next, children);
  morphChildren(el, next);
  return el;
}

/** Совпадают ли узлы настолько, чтобы старый можно было поправить, а не заменять. */
function alike(a, b) {
  if (a.nodeType !== b.nodeType) return false;
  if (a.nodeType === 3) return true;                       // текст
  if (a.tagName !== b.tagName) return false;
  const ka = a.dataset ? a.dataset.key : null;
  const kb = b.dataset ? b.dataset.key : null;
  return ka === kb;
}

function morphChildren(oldEl, newEl) {
  const olds = [...oldEl.childNodes];
  const news = [...newEl.childNodes];
  for (let i = 0; i < news.length; i++) {
    const nw = news[i];
    const old = olds[i];
    if (!old) {
      oldEl.appendChild(nw);
    } else if (alike(old, nw)) {
      morphNode(old, nw);
    } else {
      oldEl.replaceChild(nw, old);
    }
  }
  for (let i = news.length; i < olds.length; i++) oldEl.removeChild(olds[i]);
}

function morphNode(old, nw) {
  if (old.nodeType === 3) {
    if (old.nodeValue !== nw.nodeValue) old.nodeValue = nw.nodeValue;
    return;
  }
  if (old.nodeType !== 1) return;
  // обработчики берём новые: у старых в замыкании остались прежние данные
  if (old.__on) for (const [t, f] of old.__on) old.removeEventListener(t, f);
  old.__on = nw.__on;
  if (nw.__on) for (const [t, f] of nw.__on) old.addEventListener(t, f);
  for (const {name, value} of [...nw.attributes]) {
    if (old.getAttribute(name) !== value) old.setAttribute(name, value);
  }
  for (const {name} of [...old.attributes]) {
    if (!nw.hasAttribute(name)) old.removeAttribute(name);
  }
  // значение поля правим, только если оно действительно другое: иначе сбивается каретка
  if ('value' in nw && nw.tagName !== 'SELECT' && old.value !== nw.value && document.activeElement !== old) old.value = nw.value;
  if (nw.tagName === 'SELECT' && old.value !== nw.value) old.value = nw.value;
  if ('checked' in nw && old.checked !== nw.checked) old.checked = nw.checked;
  if (nw.tagName === 'DETAILS' && old.open !== nw.open) old.open = nw.open;
  morphChildren(old, nw);
}

/** Delegated event: on(root, 'click', '[data-act=x]', (e, target) => ...). */
export function on(root, type, selector, fn) {
  root.addEventListener(type, (e) => {
    const t = e.target.closest(selector);
    if (t && root.contains(t)) fn(e, t);
  });
}

/**
 * Re-renders a region keeping the focused field (found again by its data attributes) and its caret: panels that
 * refresh while something runs on the server do not steal the input.
 */
export function keepFocus(root, render) {
  const a = document.activeElement;
  const scroller = root.closest('.panel') || root;
  const top = scroller.scrollTop;
  let sel = null;
  if (a && root.contains(a) && a !== root) {
    const attrs = Object.entries(a.dataset || {}).map(([k, v]) => `[data-${k.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase())}="${CSS.escape(v)}"]`).join('');
    if (attrs) sel = {q: a.tagName.toLowerCase() + attrs, start: a.selectionStart, end: a.selectionEnd};
  }
  render();
  if (sel) {
    const b = root.querySelector(sel.q);
    if (b) {
      b.focus({preventScroll: true});   // без этого панель уезжает к полю при каждой перерисовке
      try { if (sel.start != null) b.setSelectionRange(sel.start, sel.end); } catch (e) { /* not a text field */ }
    }
  }
  if (scroller.scrollTop !== top) scroller.scrollTop = top;
}
