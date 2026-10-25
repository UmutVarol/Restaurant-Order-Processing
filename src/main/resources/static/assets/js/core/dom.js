// Tiny DOM toolkit. Text is always inserted as text nodes, never as HTML, so user-supplied values such as
// guest names and notes can never inject markup.

const SVG_NS = 'http://www.w3.org/2000/svg';

export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  applyProps(el, props);
  append(el, children);
  return el;
}

export function s(tag, props, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value == null || value === false) continue;
    if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
    else el.setAttribute(key === 'className' ? 'class' : key, value);
  }
  append(el, children);
  return el;
}

function applyProps(el, props) {
  if (!props) return;
  for (const [key, value] of Object.entries(props)) {
    if (value == null || value === false) continue;
    if (key === 'class') el.className = Array.isArray(value) ? value.filter(Boolean).join(' ') : value;
    else if (key === 'style' && typeof value === 'object') {
      for (const [prop, val] of Object.entries(value)) {
        if (prop.startsWith('--')) el.style.setProperty(prop, val);
        else el.style[prop] = val;
      }
    }
    else if (key === 'dataset') Object.assign(el.dataset, value);
    else if (key.startsWith('on') && typeof value === 'function') el.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === 'value' || key === 'checked' || key === 'disabled' || key === 'selected') el[key] = value;
    else if (value === true) el.setAttribute(key, '');
    else el.setAttribute(key, value);
  }
}

function append(el, children) {
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false || child === true) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
}

/** Replaces all children of {@code el}. */
export function mount(el, ...children) {
  el.replaceChildren();
  append(el, children);
  return el;
}

export function debounce(fn, wait = 250) {
  let timer;
  const debounced = (...args) => {
    clearTimeout(timer);
    timer = setTimeout(() => fn(...args), wait);
  };
  debounced.cancel = () => clearTimeout(timer);
  return debounced;
}

const graphemes = typeof Intl.Segmenter === 'function' ? new Intl.Segmenter(undefined, { granularity: 'grapheme' }) : null;

/** First letter of the first two words. Splits by grapheme so "Ömer" or an emoji never breaks in half. */
export function initials(name = '') {
  const first = (word) => (graphemes ? graphemes.segment(word)[Symbol.iterator]().next().value?.segment : [...word][0]) ?? '';
  return name.trim().split(/\s+/).filter(Boolean).slice(0, 2).map((word) => first(word).toLocaleUpperCase('tr')).join('');
}
