// The till's lock screen: staff pick their name and type their PIN. Only shown on a device a manager registered.
import { h, initials, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { api, ApiError, currentDevice, errorMessage, forgetDevice } from '../core/api.js';

const MAX_PIN = 6;

/** Resolves nothing; calls {@code onSignedIn} with the token response, or {@code onPassword} to fall back. */
export function lockScreen({ onSignedIn, onPassword }) {
  const device = currentDevice();
  const element = h('div', { class: 'lock' });
  let staff = null;
  let picked = null;
  let pin = '';
  let busy = false;
  let problem = '';

  const passwordLink = () => h('button', { class: 'btn ghost sm lock-alt', type: 'button', onclick: onPassword },
    icon('user', 'sm'), t('lock.usePassword'));

  function render() {
    if (picked) { renderPad(); return; }
    mount(element,
      h('div', null, h('h2', null, t('lock.title')), h('p', { class: 'muted lock-device' }, icon('lock', 'sm'), device?.name ?? '')),
      staff == null
        ? h('div', { class: 'staff-tiles' }, [1, 2, 3].map(() => h('div', { class: 'skeleton', style: { height: '112px', borderRadius: '18px' } })))
        : staff.length
          ? h('div', { class: 'staff-tiles' }, staff.map((member) => h('button', {
            class: 'staff-tile', type: 'button', 'data-testid': `staff-${member.username}`,
            onclick: () => { picked = member; pin = ''; problem = ''; render(); },
          }, h('div', { class: 'avatar' }, initials(member.displayName)), h('b', null, member.displayName),
          h('small', null, t(`role.${member.role}`)))))
          : h('div', { class: 'empty', style: { padding: '24px 8px' } }, h('div', { class: 'art' }, '🔢'), h('h4', null, t('lock.noPins')),
            h('p', null, t('lock.noPinsHint'))),
      passwordLink());
  }

  function renderPad() {
    const key = (label, onclick, extra = {}) => h('button', { class: 'pin-key', type: 'button', onclick, ...extra }, label);
    mount(element,
      h('div', { class: 'pin-who' },
        h('button', { class: 'icon-btn', type: 'button', 'aria-label': t('lock.back'), onclick: () => { picked = null; render(); } }, icon('chevronLeft')),
        h('div', { class: 'avatar' }, initials(picked.displayName)),
        h('div', null, h('b', null, picked.displayName), h('small', null, t('lock.enterPin')))),
      h('div', { class: ['pin-dots', problem ? 'shake' : ''], role: 'status', 'aria-label': t('lock.digits', { n: pin.length }) },
        Array.from({ length: Math.max(4, pin.length) }, (_, i) => h('i', { class: i < pin.length ? 'on' : '' }))),
      problem ? h('div', { class: 'form-error', role: 'alert' }, icon('alert', 'sm'), problem) : h('div', { class: 'pin-hint faint' }, t('lock.pinHint')),
      h('div', { class: 'pin-pad' },
        ['1', '2', '3', '4', '5', '6', '7', '8', '9'].map((digit) => key(digit, () => press(digit))),
        key(icon('backspace'), back, { 'aria-label': t('lock.erase') }),
        key('0', () => press('0')),
        key(busy ? h('span', { class: 'spinner' }) : icon('arrowRight'), submit,
          { class: 'pin-key go', disabled: busy || pin.length < 4, 'aria-label': t('lock.signIn'), 'data-testid': 'pin-submit' })),
      passwordLink());
  }

  function press(digit) {
    if (busy || pin.length >= MAX_PIN) return;
    pin += digit;
    problem = '';
    renderPad();
  }

  function back() {
    pin = pin.slice(0, -1);
    renderPad();
  }

  async function submit() {
    if (busy || pin.length < 4) return;
    busy = true;
    renderPad();
    try {
      const response = await api.pinLogin(picked.username, pin);
      cleanup();
      onSignedIn(response);
    } catch (error) {
      busy = false;
      pin = '';
      if (error instanceof ApiError && error.code === 'DEVICE_NOT_REGISTERED') {
        forgetDevice();
        cleanup();
        onPassword();
        return;
      }
      problem = error instanceof ApiError && error.code === 'BAD_CREDENTIALS' ? t('lock.wrongPin') : errorMessage(error);
      renderPad();
    }
  }

  const onKey = (event) => {
    if (!picked || !element.isConnected) return;
    if (/^\d$/.test(event.key)) press(event.key);
    else if (event.key === 'Backspace') back();
    else if (event.key === 'Enter') submit();
    else if (event.key === 'Escape') { picked = null; render(); }
  };
  document.addEventListener('keydown', onKey);
  const cleanup = () => document.removeEventListener('keydown', onKey);

  render();
  api.pinStaff().then((list) => { staff = list; render(); }).catch((error) => {
    if (error instanceof ApiError && error.code === 'DEVICE_NOT_REGISTERED') {
      forgetDevice();
      cleanup();
      onPassword();
      return;
    }
    staff = [];
    problem = errorMessage(error);
    render();
  });
  return element;
}
