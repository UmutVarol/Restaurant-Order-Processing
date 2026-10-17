// Overlay primitives: toasts, modals, drawers and confirmation dialogs.
import { h } from './dom.js';
import { icon } from './icons.js';
import { lang, setLang, t } from './i18n.js';
import { prefs } from './store.js';

const TOAST_ICONS = { success: 'check', error: 'alert', info: 'bell' };

export function toast({ title, text, kind = 'success', action, timeout = 4500 }) {
  const host = document.getElementById('toasts');
  const el = h('div', { class: `toast ${kind}`, role: kind === 'error' ? 'alert' : 'status' },
    h('div', { class: 'ic' }, icon(TOAST_ICONS[kind] || 'info', 'sm')),
    h('div', { class: 'msg' }, h('b', null, title), text ? h('span', null, text) : null),
    action ? h('button', { class: 'act', onclick: () => { action.run(); dismiss(); } }, action.label) : null);
  host.append(el);
  const timer = setTimeout(dismiss, timeout);
  function dismiss() {
    clearTimeout(timer);
    el.classList.add('leaving');
    el.addEventListener('animationend', () => el.remove(), { once: true });
  }
  return dismiss;
}

const openLayers = [];

document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && openLayers.length) {
    event.preventDefault();
    openLayers.at(-1).close();
  }
});

function layer(build, { onClose } = {}) {
  const previousFocus = document.activeElement;
  const overlay = h('div', { class: 'overlay' });
  let closed = false;
  const handle = {
    close() {
      if (closed) return;
      closed = true;
      overlay.remove();
      panel.remove();
      openLayers.splice(openLayers.indexOf(handle), 1);
      onClose?.();
      previousFocus?.focus?.();
    },
  };
  const panel = build(handle);
  overlay.addEventListener('click', handle.close);
  document.body.append(overlay, panel);
  openLayers.push(handle);
  requestAnimationFrame(() => panel.querySelector('[autofocus], input, textarea, select, button.primary')?.focus());
  handle.el = panel;
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
    handle.render = (...args) => panel.replaceChildren(...[render(handle, ...args)].flat(Infinity).filter((n) => n != null && n !== false));
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
    const field = input ? h('textarea', { class: 'textarea', placeholder: input.placeholder || '', rows: 3 }) : null;
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
        field ? h('div', { class: 'field' }, h('label', null, input.label), field) : null,
      ],
      footer: (handle) => {
        const confirm = h('button', {
          class: `btn ${danger ? 'danger' : 'primary'}`,
          onclick: () => {
            if (field && input.required && !field.value.trim()) {
              field.classList.add('invalid');
              field.focus();
              return;
            }
            settle(field ? field.value.trim() : true, handle);
          },
        }, confirmLabel);
        if (danger) confirm.style.cssText = 'background: var(--red); color: #fff; border-color: transparent;';
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
    class: 'icon-btn bordered', title: t('action.langTitle'), 'aria-label': t('action.langTitle'),
    onclick: () => setLang(lang() === 'tr' ? 'en' : 'tr'),
  }, h('span', { style: { fontWeight: 700, fontSize: '12px', letterSpacing: '.04em' } }, lang() === 'tr' ? 'EN' : 'TR'));
}
