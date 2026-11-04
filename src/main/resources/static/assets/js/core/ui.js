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

export function toast({ title, text, kind = 'success', action, timeout = 4500 }) {
  const host = document.getElementById('toasts');
  const el = h('div', { class: `toast ${kind}`, role: kind === 'error' ? 'alert' : 'status' },
    h('div', { class: 'ic' }, icon(TOAST_ICONS[kind] || 'info', 'sm')),
    h('div', { class: 'msg' }, h('b', null, title), text ? h('span', null, text) : null),
    action ? h('button', { class: 'act', onclick: () => { action.run(); dismiss(); } }, action.label) : null);
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
  return dismiss;
}

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
