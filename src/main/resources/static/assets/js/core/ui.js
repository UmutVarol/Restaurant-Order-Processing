// Overlay primitives: toasts, modals, drawers and confirmation dialogs.
import { h } from './dom.js';
import { icon } from './icons.js';
import { lang, LANGUAGES, setLang, t } from './i18n.js';
import { prefs } from './store.js';

const TOAST_ICONS = { success: 'check', error: 'alert', info: 'bell' };
const MAX_TOASTS = 3;
const PHONE = matchMedia('(max-width: 720px)');
const REDUCED_MOTION = matchMedia('(prefers-reduced-motion: reduce)');

/** Whether the screen is phone-sized: dialogs and drawers then open as sheets from the bottom. */
export const isPhone = () => PHONE.matches;

/**
 * A short buzz under the finger for what a tap did, on phones that can (Android; iOS ignores it). Skipped when the
 * user asked for less motion.
 */
export function haptic(kind = 'tap') {
  if (REDUCED_MOTION.matches || !navigator.vibrate) return;
  try { navigator.vibrate({ tap: 8, success: [10, 40, 14], error: [30, 50, 30] }[kind] ?? 8); } catch { /* not allowed */ }
}

// ------------------------------------------------------------------ motion (DESIGN.md: transform and opacity only)

/** Whether the person asked for less motion: every helper below then jumps straight to the end state. */
export const reducedMotion = () => REDUCED_MOTION.matches;

/**
 * Counts a figure up to its value once, when it first appears (0.6 s, easing out). Digits keep their width
 * (tabular figures), so nothing around it moves. {@code format} turns the number into its text (money, plain).
 */
export function countUp(el, to, format = (v) => String(Math.round(v)), duration = 600) {
  if (!el) return;
  const target = Number(to) || 0;
  if (reducedMotion() || target === 0) { el.textContent = format(target); return; }
  const start = performance.now();
  const step = (now) => {
    const p = Math.min(1, (now - start) / duration);
    el.textContent = format(target * (1 - Math.pow(1 - p, 3)));
    if (p < 1 && el.isConnected) requestAnimationFrame(step);
    else el.textContent = format(target);
  };
  el.textContent = format(0);
  requestAnimationFrame(step);
}

/** Lets the first rows of a list settle into place one after another (40 ms apart), the rest at once. */
export function reveal(container, selector, max = 8) {
  if (!container || reducedMotion()) return;
  [...container.querySelectorAll(selector)].slice(0, max).forEach((el, i) => {
    el.style.animationDelay = `${i * 40}ms`;
    el.classList.add('reveal');
    el.addEventListener('animationend', () => { el.classList.remove('reveal'); el.style.animationDelay = ''; }, { once: true });
  });
}

/**
 * A number whose changed digits roll (like a station's departure board); the others stay put. Digits read left to
 * right in every language. Pass the value shown before ({@code from}) to roll from it: the element may be a fresh one
 * from a re-render.
 */
export function flipNumber(el, value, from) {
  const text = String(value);
  const before = from == null ? null : String(from);
  const digit = (d) => h('span', { class: 'flip-digit', 'aria-hidden': 'true' },
    h('span', { class: 'flip-strip', style: { transform: `translateY(-${d}em)` } }, '0123456789'.split('').map((n) => h('i', null, n))));
  el.classList.add('flip-number');
  el.setAttribute('aria-label', text);
  el.replaceChildren(...[...text].map((ch) => (/\d/.test(ch) ? digit(Number(ch)) : h('span', { 'aria-hidden': 'true' }, ch))));
  if (before == null || before === text || before.length !== text.length || reducedMotion()) return el;
  [...text].forEach((ch, i) => {
    const old = before[i];
    if (old === ch || !/\d/.test(ch) || !/\d/.test(old)) return;
    el.children[i].firstElementChild.animate([{ transform: `translateY(-${Number(old)}em)` }, { transform: `translateY(-${Number(ch)}em)` }],
      { duration: 420, easing: 'cubic-bezier(.2, .8, .2, 1)' });
  });
  return el;
}

/** Makes a badge grow in once when its number goes up (no bounce). */
export function bump(el) {
  if (!el || reducedMotion()) return;
  el.classList.remove('bump');
  void el.offsetWidth;
  el.classList.add('bump');
}

/**
 * Moves items to where a re-render put them by sliding from where they were (FLIP): measure, re-render, then play the
 * difference. Items that did not move are left alone; with less motion asked for, the moved ones are outlined for a
 * moment instead.
 *
 * @param key data attribute naming each item (the same item keeps the same key across renders)
 * @param render the re-render
 * @param changed keys whose items should be pointed out (the one a person just moved)
 */
export function flip(container, key, render, changed = []) {
  const first = new Map([...container.querySelectorAll(`[data-${key}]`)].map((el) => [el.dataset[key], el.getBoundingClientRect()]));
  render();
  const marked = new Set(changed.map(String));
  container.querySelectorAll(`[data-${key}]`).forEach((el) => {
    if (reducedMotion()) {
      if (marked.has(el.dataset[key])) { el.classList.add('flip-moved'); setTimeout(() => el.classList.remove('flip-moved'), 1000); }
      return;
    }
    const before = first.get(el.dataset[key]);
    if (!before) return;
    const after = el.getBoundingClientRect();
    const dx = before.left - after.left;
    const dy = before.top - after.top;
    if (Math.abs(dx) < 1 && Math.abs(dy) < 1) return;
    el.animate([{ transform: `translate(${dx}px, ${dy}px)` }, { transform: 'none' }],
      { duration: marked.has(el.dataset[key]) ? 420 : 240, easing: 'cubic-bezier(.2, .8, .2, 1)' });
  });
}

/**
 * Lets a sheet be dragged down and let go to close it, the way phone sheets behave. Only the sheet's top (its grab
 * handle and heading) starts a drag, so scrolling the content never closes it by accident.
 *
 * @param grabSelector what inside the sheet starts a drag, besides the top 28px with the handle
 */
export function swipeToClose(sheet, close, grabSelector) {
  let start = null;
  sheet.addEventListener('pointerdown', (event) => {
    if (!isPhone() || event.button !== 0 || event.target.closest('button, a, input, select, textarea, [contenteditable]')) return;
    const fromTop = event.clientY - sheet.getBoundingClientRect().top;
    if (fromTop > 28 && !event.target.closest(grabSelector)) return;
    start = { y: event.clientY, at: performance.now(), id: event.pointerId };
    sheet.style.transition = 'none';
  });
  sheet.addEventListener('pointermove', (event) => {
    if (!start || event.pointerId !== start.id) return;
    const dy = Math.max(0, event.clientY - start.y);
    if (dy > 4 && !sheet.hasPointerCapture(event.pointerId)) sheet.setPointerCapture(event.pointerId);
    sheet.style.transform = `translateY(${dy}px)`;
  });
  const end = (event) => {
    if (!start || event.pointerId !== start.id) return;
    const dy = Math.max(0, event.clientY - start.y);
    const speed = dy / Math.max(1, performance.now() - start.at);
    start = null;
    sheet.style.transition = '';
    if (dy > 120 || (dy > 40 && speed > 0.6)) {
      sheet.style.transform = 'translateY(100%)';
      close();
    } else {
      sheet.style.transform = '';
    }
  };
  sheet.addEventListener('pointerup', end);
  sheet.addEventListener('pointercancel', end);
}

/** Removes an element once its exit transition ends (or straight away when transitions are off). */
function leave(el, className) {
  el.classList.add(className);
  const done = () => el.remove();
  el.addEventListener('transitionend', done, { once: true });
  setTimeout(done, 400);
}

export function toast({ title, text, kind = 'success', action, timeout = 4500, countdown = 0 }) {
  const host = document.getElementById('toasts');
  const el = h('div', { class: ['toast', kind, countdown ? 'counting' : ''], role: kind === 'error' ? 'alert' : 'status' },
    h('div', { class: 'ic' }, icon(TOAST_ICONS[kind] || 'info', 'sm')),
    h('div', { class: 'msg' }, h('b', null, title), text ? h('span', null, text) : null),
    action ? h('button', { class: 'act', 'data-testid': 'toast-action', onclick: () => { action.run(); dismiss(); } }, action.label) : null,
    // E1: how long is left to take it back, as a bar that empties (reduced motion: the seconds in the text only)
    countdown ? h('i', { class: 'toast-countdown', 'aria-hidden': 'true', style: { animationDuration: `${countdown}ms` } }) : null);
  host.append(el);
  // A burst of events (a busy kitchen) should not bury the screen: keep the newest few.
  const live = [...host.querySelectorAll('.toast:not(.leaving)')];
  live.slice(0, Math.max(0, live.length - MAX_TOASTS)).forEach((old) => old.dismiss?.());
  let timer = setTimeout(dismiss, timeout);
  // Hovering or focusing a toast pauses it, so an action button never vanishes under the pointer.
  const pause = () => clearTimeout(timer);
  const resume = () => { clearTimeout(timer); timer = setTimeout(dismiss, 2000); };
  el.addEventListener('pointerenter', pause);
  el.addEventListener('pointerleave', resume);
  el.addEventListener('focusin', pause);
  el.addEventListener('focusout', resume);
  let gone = false;
  function dismiss() {
    if (gone) return;
    gone = true;
    clearTimeout(timer);
    leave(el, 'leaving');
  }
  el.dismiss = dismiss;
  dismiss.el = el;
  return dismiss;
}

// ---------------------------------------------------------------------------- E1: undo instead of a confirmation

const UNDO_MS = 5000;
const pendingUndos = new Set();

/**
 * Does something on screen at once and sends it a few seconds later, unless the user takes it back from the toast.
 * {@code apply} and {@code revert} change only the screen; {@code commit} talks to the server. Leaving the page or
 * the screen sends whatever is still waiting, so nothing is lost. Resolves once it is sent or taken back.
 */
export function undoable({ title, apply, revert, commit, onError, delay = UNDO_MS }) {
  return new Promise((resolve) => {
    apply?.();
    let settled = false;
    let left = Math.ceil(delay / 1000);
    const entry = { flush: () => finish(true) };
    pendingUndos.add(entry);
    const dismiss = toast({
      title, text: t('undo.left', { n: left }), kind: 'success', timeout: delay + 60_000, countdown: delay,
      action: { label: t('undo.action'), run: () => finish(false) },
    });
    const seconds = dismiss.el.querySelector('.msg span');
    const tick = setInterval(() => { left = Math.max(0, left - 1); if (seconds) seconds.textContent = t('undo.left', { n: left }); }, 1000);
    const timer = setTimeout(() => finish(true), delay);
    async function finish(send) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      clearInterval(tick);
      pendingUndos.delete(entry);
      dismiss();
      if (!send) {
        revert?.();
        toast({ title: t('undo.done'), kind: 'info', timeout: 1800 });
        resolve(false);
        return;
      }
      try {
        await commit();
        resolve(true);
      } catch (error) {
        revert?.();
        onError?.(error);
        resolve(false);
      }
    }
  });
}

/** Sends everything still waiting to be undone: the screen is changing or the page is going away. */
export function flushUndoables() {
  [...pendingUndos].forEach((entry) => entry.flush());
}
window.addEventListener('pagehide', flushUndoables);

const openLayers = [];

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && openLayers.length) {
    event.preventDefault();
    openLayers.at(-1).close();
  }
});

// While a dialog is open the app behind it is inert: no focus, no clicks, hidden from screen readers.
function syncInert() {
  const app = document.getElementById('app');
  if (app) app.inert = openLayers.length > 0;
  openLayers.forEach((open, index) => { open.el && (open.el.inert = index !== openLayers.length - 1); });
}

function layer(build, { onClose } = {}) {
  const previousFocus = document.activeElement;
  const overlay = h('div', { class: 'overlay' });
  let closed = false;
  const handle = {
    close() {
      if (closed) return;
      closed = true;
      leave(overlay, 'closing');
      leave(panel, 'closing');
      openLayers.splice(openLayers.indexOf(handle), 1);
      syncInert();
      onClose?.();
      previousFocus?.focus?.();
    },
  };
  const panel = build(handle);
  overlay.addEventListener('click', handle.close);
  swipeToClose(panel, handle.close, '.modal-head, .drawer-head');
  document.body.append(overlay, panel);
  openLayers.push(handle);
  syncInert();
  // Only when nothing inside has focus yet: a fast typist or a barcode scanner may already be in the second field.
  // On a phone the sheet itself takes focus: a keyboard popping up at once would cover half of it.
  requestAnimationFrame(() => {
    if (panel.contains(document.activeElement)) return;
    if (isPhone()) {
      panel.tabIndex = -1;
      panel.focus({ preventScroll: true });
    } else {
      panel.querySelector('[autofocus], input, textarea, select, button.primary')?.focus();
    }
  });
  handle.el = panel;
  syncInert();
  return handle;
}

export function openModal({ title, body, footer, size = '', onClose }) {
  return layer((handle) => h('div', { class: `modal ${size}`, role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    h('div', { class: 'modal-head' },
      h('h2', null, title),
      h('button', { class: 'icon-btn', 'aria-label': t('common.close'), onclick: handle.close }, icon('x'))),
    h('div', { class: 'modal-body' }, typeof body === 'function' ? body(handle) : body),
    footer ? h('div', { class: 'modal-foot' }, typeof footer === 'function' ? footer(handle) : footer) : null), { onClose });
}

export function openDrawer(render, { onClose } = {}) {
  return layer((handle) => {
    const panel = h('aside', { class: 'drawer', role: 'dialog', 'aria-modal': 'true' });
    handle.render = (...args) => {
      panel.replaceChildren(...[render(handle, ...args)].flat(Infinity).filter((n) => n != null && n !== false));
      const heading = panel.querySelector('h2[id]');
      if (heading) panel.setAttribute('aria-labelledby', heading.id);
    };
    handle.render();
    return panel;
  }, { onClose });
}

/**
 * Asks for confirmation. With {@code input}, resolves to the entered text; otherwise to {@code true}.
 * Resolves to {@code null} when dismissed.
 */
export function confirmDialog({ title, text, confirmLabel, danger = false, input }) {
  return new Promise((resolve) => {
    let settled = false;
    const field = input ? h('textarea', { class: 'textarea', placeholder: input.placeholder || '', rows: 3, maxlength: input.maxlength || 255, id: 'confirm-input' }) : null;
    const settle = (value, handle) => {
      settled = true;
      resolve(value);
      handle.close();
    };
    openModal({
      title,
      size: 'sm',
      body: [
        text ? h('p', { class: 'muted' }, text) : null,
        field ? h('div', { class: 'field' }, h('label', { for: 'confirm-input' }, input.label), field) : null,
      ],
      footer: (handle) => {
        const confirm = h('button', {
          class: `btn ${danger ? 'danger-solid' : 'primary'}`,
          onclick: () => {
            if (field && input.required && !field.value.trim()) {
              field.classList.add('invalid');
              field.focus();
              return;
            }
            settle(field ? field.value.trim() : true, handle);
          },
        }, confirmLabel);
        return [h('button', { class: 'btn ghost', onclick: () => handle.close() }, t('common.cancel')), confirm];
      },
      onClose: () => { if (!settled) resolve(null); },
    });
  });
}

export function themeButton() {
  return h('button', {
    class: 'icon-btn bordered', id: 'theme-btn', title: t('action.theme'), 'aria-label': t('action.theme'),
    onclick: () => { prefs.theme = prefs.theme === 'dark' ? 'light' : 'dark'; },
  }, icon(prefs.theme === 'dark' ? 'sun' : 'moon'));
}

export function langButton() {
  return h('button', {
    class: 'icon-btn bordered', title: t('action.langTitle'), 'aria-label': t('action.langTitle'), 'data-testid': 'lang-button',
    onclick: () => chooseLanguage(),
  }, h('span', { class: 'lang-code' }, lang().toUpperCase()));
}

/** Lists the languages by their own names; picking one switches the whole app at once. */
export function chooseLanguage() {
  const modal = openModal({
    title: t('action.langTitle'),
    size: 'sm',
    body: h('div', { class: 'lang-list', role: 'radiogroup', 'aria-label': t('action.langTitle') }, LANGUAGES.map((language) => h('button', {
      type: 'button', class: ['lang-option', language.code === lang() ? 'active' : ''], role: 'radio',
      'aria-checked': String(language.code === lang()), lang: language.code, dir: language.dir ?? 'ltr',
      'data-testid': `lang-${language.code}`,
      onclick: async () => {
        modal.close();
        await setLang(language.code);
      },
    }, h('span', null, language.name), h('span', { class: 'lang-tag' }, language.code.toUpperCase()),
    language.code === lang() ? icon('check', 'sm') : null))),
  });
  return modal;
}

// ---------------------------------------------------------------------------- E2: swipe a card to its next step

/**
 * Lets a card be swiped towards the reading end (right, or left in Arabic) to do its main action: a green band with
 * the action's name shows under it, and past 40% of its width it goes. Let go sooner and it slides back. Only a
 * clearly sideways move counts, so scrolling the list up and down is untouched. The card's own button stays: the
 * swipe is a shortcut, never the only way.
 */
export function swipeAction(card, { label, run }) {
  let drag = null;
  let suppressClick = false;
  card.classList.add('swipeable');

  const forward = () => (getComputedStyle(card).direction === 'rtl' ? -1 : 1);
  function under() {
    const band = h('div', { class: 'swipe-under', 'aria-hidden': 'true' }, icon('check', 'sm'), h('span', null, label));
    band.style.top = `${card.offsetTop}px`;
    band.style.left = `${card.offsetLeft}px`;
    band.style.width = `${card.offsetWidth}px`;
    band.style.height = `${card.offsetHeight}px`;
    card.parentElement.insertBefore(band, card);
    return band;
  }
  function reset() {
    drag?.band?.remove();
    card.classList.remove('swiping');
    card.style.transform = '';
    drag = null;
  }

  card.addEventListener('pointerdown', (event) => {
    if (event.button !== 0 || drag) return;
    drag = { x: event.clientX, y: event.clientY, dx: 0, axis: null, past: false, id: event.pointerId };
  });
  card.addEventListener('pointermove', (event) => {
    if (!drag || event.pointerId !== drag.id) return;
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    if (drag.axis == null) {
      if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return;
      drag.axis = Math.abs(dx) > Math.abs(dy) * 1.5 ? 'x' : 'y';
      if (drag.axis === 'y') return;
      card.setPointerCapture(event.pointerId);
      card.classList.add('swiping');
      drag.band = under();
    }
    if (drag.axis !== 'x') return;
    // only towards the reading end; a pull the other way stays put
    drag.dx = forward() > 0 ? Math.max(0, dx) : Math.min(0, dx);
    card.style.transform = `translateX(${drag.dx}px)`;
    const past = Math.abs(drag.dx) > card.offsetWidth * 0.4;
    if (past !== drag.past) {
      drag.past = past;
      drag.band.classList.toggle('past', past);
      if (past) haptic();
    }
  });
  const release = (event) => {
    if (!drag || event.pointerId !== drag.id) return;
    if (drag.axis !== 'x') { drag = null; return; }
    suppressClick = true;
    setTimeout(() => { suppressClick = false; }, 0);
    const { past, band } = drag;
    card.classList.remove('swiping');
    if (past) {
      card.classList.add('swipe-gone');
      card.style.transform = `translateX(${forward() * card.offsetWidth}px)`;
      setTimeout(() => { band?.remove(); drag = null; run(); }, reducedMotion() ? 0 : 180);
      return;
    }
    card.classList.add('swipe-back');
    card.style.transform = '';
    setTimeout(() => { card.classList.remove('swipe-back'); band?.remove(); }, reducedMotion() ? 0 : 240);
    drag = null;
  };
  card.addEventListener('pointerup', release);
  card.addEventListener('pointercancel', (event) => { if (drag && event.pointerId === drag.id) reset(); });
  // the click that ends a swipe must not also press a button inside the card
  card.addEventListener('click', (event) => { if (suppressClick) { event.stopPropagation(); event.preventDefault(); } }, true);
}
