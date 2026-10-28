// Settings: my PIN for quick switching, this till's registration, and for managers the registered devices.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { dateTime } from '../core/format.js';
import { api, currentDevice, errorMessage, forgetDevice, saveDevice, updateDevice } from '../core/api.js';
import { confirmDialog, toast } from '../core/ui.js';
import { withRecentSignIn } from './reauth.js';

const IDLE_CHOICES = [1, 2, 5, 10, 0];

export default {
  title: 'settings.title',
  subtitle: 'settings.sub',
  mount(container, { user, navigate }) {
    const manager = user.role === 'ADMIN';
    const pinCard = h('div', { class: 'card settings-card' });
    const deviceCard = h('div', { class: 'card settings-card' });
    const devicesCard = h('div', { class: 'card settings-card' });
    let devices = null;
    mount(container, h('div', { class: 'settings-grid' }, pinCard, deviceCard, manager ? devicesCard : null));

    const head = (iconName, title, sub) => h('div', { class: 'card-head' },
      h('span', { class: 'settings-ic' }, icon(iconName, 'sm')),
      h('div', null, h('div', { class: 'card-title' }, title), h('div', { class: 'card-sub' }, sub)));

    // ------------------------------------------------------------------ my PIN
    function renderPin() {
      const password = h('input', { class: 'input', id: 'pin-password', type: 'password', autocomplete: 'current-password' });
      const pin = h('input', { class: 'input pin-input', id: 'pin-new', type: 'password', inputmode: 'numeric', autocomplete: 'off', maxlength: 6, pattern: '\\d{4,6}' });
      const again = h('input', { class: 'input pin-input', id: 'pin-again', type: 'password', inputmode: 'numeric', autocomplete: 'off', maxlength: 6 });
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      const save = h('button', { class: 'btn primary', type: 'submit', 'data-testid': 'save-pin' }, icon('check', 'sm'), t('settings.savePin'));
      const fail = (message, field) => { problem.replaceChildren(icon('alert', 'sm'), message); problem.hidden = false; field?.focus(); };
      mount(pinCard,
        head('lock', t('settings.pinTitle'), user.hasPin ? t('settings.pinSet') : t('settings.pinNone')),
        h('form', {
          class: 'card-body settings-form',
          onsubmit: async (event) => {
            event.preventDefault();
            problem.hidden = true;
            if (!password.value) return fail(t('settings.needPassword'), password);
            if (!/^\d{4,6}$/.test(pin.value)) return fail(t('settings.pinFormat'), pin);
            if (pin.value !== again.value) return fail(t('settings.pinMismatch'), again);
            save.disabled = true;
            try {
              await api.setPin(password.value, pin.value);
              user.hasPin = true;
              toast({ title: t('settings.pinSaved') });
              renderPin();
            } catch (error) {
              fail(errorMessage(error));
              save.disabled = false;
            }
          },
        },
        h('p', { class: 'muted' }, t('settings.pinText')),
        h('div', { class: 'field' }, h('label', { for: 'pin-password' }, t('login.password')), password),
        h('div', { class: 'grid-2' },
          h('div', { class: 'field' }, h('label', { for: 'pin-new' }, t('settings.newPin')), pin),
          h('div', { class: 'field' }, h('label', { for: 'pin-again' }, t('settings.repeatPin')), again)),
        problem,
        h('div', null, save)));
    }

    // ------------------------------------------------------------------ this device
    function renderDevice() {
      const device = currentDevice();
      if (device) {
        const idle = h('select', {
          class: 'select', id: 'idle-lock', disabled: !manager,
          onchange: (event) => { updateDevice({ idleMinutes: Number(event.target.value) }); toast({ title: t('settings.saved') }); },
        }, IDLE_CHOICES.map((minutes) => h('option', { value: minutes, selected: (device.idleMinutes ?? 5) === minutes },
          minutes ? t('settings.idleMinutes', { n: minutes }) : t('settings.idleNever'))));
        mount(deviceCard,
          head('pos', t('settings.deviceTitle'), t('settings.deviceRegistered', { name: device.name })),
          h('div', { class: 'card-body settings-form' },
            h('p', { class: 'muted' }, t('settings.deviceText')),
            h('div', { class: 'field' }, h('label', { for: 'idle-lock' }, t('settings.idleLock')), idle),
            manager ? h('div', null, h('button', {
              class: 'btn ghost', onclick: async () => {
                if (!(await confirmDialog({ title: t('settings.forgetTitle'), text: t('settings.forgetText'), confirmLabel: t('settings.forget') }))) return;
                forgetDevice();
                navigate('settings');
              },
            }, icon('x', 'sm'), t('settings.forget'))) : null));
        return;
      }
      if (!manager) {
        mount(deviceCard, head('pos', t('settings.deviceTitle'), t('settings.deviceNone')),
          h('div', { class: 'card-body' }, h('p', { class: 'muted' }, t('settings.askManager'))));
        return;
      }
      const name = h('input', { class: 'input', id: 'device-name', maxlength: 60, value: t('settings.defaultDeviceName') });
      mount(deviceCard,
        head('pos', t('settings.deviceTitle'), t('settings.deviceNone')),
        h('form', {
          class: 'card-body settings-form',
          onsubmit: async (event) => {
            event.preventDefault();
            if (!name.value.trim()) { name.focus(); return; }
            try {
              const registration = await withRecentSignIn(() => api.registerDevice(name.value.trim()));
              if (!registration) return;
              saveDevice(registration);
              toast({ title: t('settings.registered', { name: registration.device.name }) });
              navigate('settings');
            } catch (error) {
              toast({ title: errorMessage(error), kind: 'error' });
            }
          },
        },
        h('p', { class: 'muted' }, t('settings.registerText')),
        h('div', { class: 'field' }, h('label', { for: 'device-name' }, t('settings.deviceName')), name),
        h('div', null, h('button', { class: 'btn primary', type: 'submit', 'data-testid': 'register-device' }, icon('lock', 'sm'), t('settings.register')))));
    }

    // ------------------------------------------------------------------ all devices (managers)
    function renderDevices() {
      if (!manager) return;
      const here = currentDevice()?.id;
      mount(devicesCard,
        head('users', t('settings.devicesTitle'), t('settings.devicesSub')),
        devices == null ? h('div', { class: 'card-body' }, h('div', { class: 'skeleton', style: { height: '80px' } }))
          : !devices.length ? h('div', { class: 'empty', style: { padding: '24px' } }, h('div', { class: 'art' }, '🖥️'), h('h4', null, t('settings.noDevices')))
            : h('div', { class: 'table-wrap' }, h('table', { class: 'data-table' },
              h('thead', null, h('tr', null, h('th', null, t('settings.deviceName')), h('th', { class: 'hide-sm' }, t('settings.registeredBy')),
                h('th', { class: 'hide-sm' }, t('settings.lastSeen')), h('th', null, ''))),
              h('tbody', null, devices.map((device) => h('tr', { class: device.active ? '' : 'muted-row' },
                h('td', null, h('b', null, device.name), device.id === here ? h('span', { class: 'pill plain', style: { marginLeft: '8px' } }, t('settings.thisDevice')) : null,
                  h('small', { class: 'faint', style: { display: 'block' } }, device.active ? dateTime(device.createdAt) : t('settings.revoked'))),
                h('td', { class: 'hide-sm' }, device.createdBy),
                h('td', { class: 'hide-sm' }, device.lastSeenAt ? dateTime(device.lastSeenAt) : '–'),
                h('td', { class: 'right' }, device.active ? h('button', {
                  class: 'btn sm ghost danger-text', onclick: () => revoke(device),
                }, t('settings.revoke')) : null)))))));
    }

    async function revoke(device) {
      if (!(await confirmDialog({ title: t('settings.revokeTitle', { name: device.name }), text: t('settings.revokeText'), confirmLabel: t('settings.revoke'), danger: true }))) return;
      try {
        const revoked = await withRecentSignIn(() => api.revokeDevice(device.id));
        if (!revoked) return;
        if (revoked.id === currentDevice()?.id) forgetDevice();
        toast({ title: t('settings.revokedToast', { name: device.name }) });
        loadDevices();
        renderDevice();
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    async function loadDevices() {
      if (!manager) return;
      try {
        devices = await api.devices();
      } catch (error) {
        devices = [];
        toast({ title: errorMessage(error), kind: 'error' });
      }
      renderDevices();
    }

    renderPin();
    renderDevice();
    renderDevices();
    loadDevices();
    return null;
  },
};
