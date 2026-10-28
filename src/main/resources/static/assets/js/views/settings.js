// Settings: my PIN for quick switching, this till's registration, and for managers the registered devices.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t } from '../core/i18n.js';
import { dateTime } from '../core/format.js';
import { api, currentDevice, errorMessage, forgetDevice, saveDevice, updateDevice } from '../core/api.js';
import { bus } from '../core/store.js';
import { confirmDialog, openModal, toast } from '../core/ui.js';
import { qrCode } from './qr.js';
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
    const qrCard = h('div', { class: 'card settings-card' });
    const printersCard = h('div', { class: 'card settings-card' });
    let devices = null;
    let tables = null;
    let printers = null;
    mount(container, h('div', { class: 'settings-grid' }, pinCard, deviceCard, manager ? devicesCard : null, manager ? qrCard : null,
      manager ? printersCard : null));

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

    // ------------------------------------------------------------------ table QR codes (managers)
    // A code is shown once, when it is made; printing new codes is how a table's old code is retired.
    const tableLink = (token) => `${location.origin}${location.pathname}#/t/${token}`;

    function renderQr() {
      if (!manager) return;
      mount(qrCard,
        head('table', t('qr.title'), t('qr.sub')),
        h('div', { class: 'card-body settings-form' },
          h('p', { class: 'muted' }, t('qr.text')),
          tables == null ? h('div', { class: 'skeleton', style: { height: '80px' } })
            : h('div', { class: 'qr-tables' }, tables.map((table) => h('button', {
              type: 'button', class: 'qr-table', 'data-testid': `qr-${table.code}`, onclick: () => printCodes([table]),
            }, h('b', null, table.code), h('small', null, table.qrIssuedAt ? dateTime(table.qrIssuedAt) : t('qr.none'))))),
          tables?.length ? h('div', null, h('button', { class: 'btn', 'data-testid': 'qr-all', onclick: () => printCodes(tables) },
            icon('printer', 'sm'), t('qr.printAll'))) : null));
    }

    async function printCodes(list) {
      const replacing = list.some((table) => table.qrIssuedAt);
      if (replacing && !(await confirmDialog({
        title: list.length === 1 ? t('qr.replaceOne', { table: list[0].code }) : t('qr.replaceAll'),
        text: t('qr.replaceText'), confirmLabel: t('qr.makeNew'),
      }))) return;
      const issued = [];
      try {
        for (const table of list) issued.push(await api.issueTableQr(table.id));
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
      if (!issued.length) return;
      loadTables();
      const sheet = () => h('div', { class: 'qr-sheet' }, issued.map((code) => h('div', { class: 'qr-tent' },
        h('div', { class: 'qr-tent-head' }, h('b', null, t('guest.table', { table: code.tableCode }))),
        qrCode(tableLink(code.token), { label: t('guest.table', { table: code.tableCode }) }),
        h('div', { class: 'qr-tent-foot' }, t('qr.scan')))));
      openModal({
        title: t('qr.ready', { n: issued.length }),
        body: [h('p', { class: 'muted' }, t('qr.readyText')), sheet(),
          issued.length === 1 ? h('a', { class: 'faint qr-link', href: tableLink(issued[0].token), target: '_blank', rel: 'noopener', 'data-testid': 'qr-link' }, t('qr.openLink')) : null],
        footer: (handle) => [
          h('button', { class: 'btn ghost', onclick: handle.close }, t('common.close')),
          h('button', { class: 'btn primary', onclick: () => { document.getElementById('print-root').replaceChildren(sheet()); window.print(); } },
            icon('printer', 'sm'), t('detail.printNow')),
        ],
      });
    }

    // ------------------------------------------------------------------ printers (managers)
    function renderPrinters() {
      if (!manager) return;
      mount(printersCard,
        head('printer', t('printing.title'), t('printing.sub')),
        h('div', { class: 'card-body settings-form' },
          h('p', { class: 'muted' }, t('printing.text')),
          printers == null ? h('div', { class: 'skeleton', style: { height: '80px' } })
            : printers.length ? h('div', { class: 'printer-list' }, printers.map((printer) => h('div', { class: ['printer-row', printer.enabled ? '' : 'off'] },
              h('div', { class: 'printer-info' },
                h('b', null, printer.name, printer.enabled ? null : h('span', { class: 'pill plain', style: { marginLeft: '8px' } }, t('printing.off'))),
                h('small', null, `${printer.host}:${printer.port} · ${printer.paperWidth} mm · ${printer.categories.map((c) => t(`cat.${c}`)).join(', ')}`),
                printer.lastError ? h('small', { class: 'danger-text' }, icon('alert', 'sm'), ` ${printer.lastError}`)
                  : printer.lastPrintedAt ? h('small', { class: 'faint' }, t('printing.lastPrinted', { at: dateTime(printer.lastPrintedAt) })) : null),
              h('div', { class: 'printer-actions' },
                h('button', { class: 'btn sm ghost', onclick: () => preview(printer) }, icon('eye', 'sm'), t('printing.preview')),
                h('button', { class: 'btn sm ghost', 'data-testid': `test-${printer.name}`, onclick: () => testPage(printer) }, icon('printer', 'sm'), t('printing.test')),
                h('button', { class: 'btn sm ghost', onclick: () => editPrinter(printer) }, icon('edit', 'sm'), t('common.edit'))))))
              : h('div', { class: 'empty', style: { padding: '18px' } }, h('div', { class: 'art' }, '🖨️'), h('h4', null, t('printing.none'))),
          h('div', null, h('button', { class: 'btn', 'data-testid': 'add-printer', onclick: () => editPrinter(null) }, icon('plus', 'sm'), t('printing.add')))));
    }

    function editPrinter(printer) {
      const draft = printer ?? { name: '', host: '192.168.1.', port: 9100, paperWidth: 80, categories: ['STARTER', 'MAIN'], enabled: true };
      const input = (id, value, extra = {}) => h('input', { class: 'input', id, value, ...extra });
      const name = input('printer-name', draft.name, { maxlength: 40 });
      const host = input('printer-host', draft.host, { maxlength: 100, inputmode: 'decimal', autocomplete: 'off' });
      const port = input('printer-port', draft.port, { type: 'number', min: 1, max: 65535 });
      const width = h('select', { class: 'select', id: 'printer-width' }, [80, 58].map((w) => h('option', { value: w, selected: draft.paperWidth === w }, `${w} mm`)));
      const boxes = ['STARTER', 'MAIN', 'DESSERT', 'DRINK'].map((c) => h('label', null,
        h('input', { type: 'checkbox', value: c, checked: draft.categories.includes(c) }), t(`cat.${c}`)));
      const enabled = h('input', { type: 'checkbox', checked: draft.enabled });
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      const field = (id, label, control) => h('div', { class: 'field' }, h('label', { for: id }, label), control);
      const modal = openModal({
        title: printer ? t('printing.edit', { name: printer.name }) : t('printing.add'),
        size: 'sm',
        body: [
          field('printer-name', t('printing.name'), name),
          h('div', { class: 'grid-2' }, field('printer-host', t('printing.host'), host), field('printer-port', t('printing.port'), port)),
          h('small', { class: 'faint' }, t('printing.hostHint')),
          field('printer-width', t('printing.paper'), width),
          h('div', { class: 'field' }, h('span', { class: 'label' }, t('printing.categories')), h('div', { class: 'tag-picker' }, boxes)),
          h('label', { class: 'checkbox' }, enabled, t('printing.enabled')),
          problem,
        ],
        footer: (handle) => [
          printer ? h('button', { class: 'btn ghost danger-text', onclick: async () => {
            if (!(await confirmDialog({ title: t('printing.removeTitle', { name: printer.name }), text: t('printing.removeText'), confirmLabel: t('printing.remove'), danger: true }))) return;
            try { await api.deletePrinter(printer.id); handle.close(); loadPrinters(); } catch (error) { toast({ title: errorMessage(error), kind: 'error' }); }
          } }, t('printing.remove')) : null,
          h('span', { style: { flex: 1 } }),
          h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')),
          h('button', { class: 'btn primary', 'data-testid': 'save-printer', onclick: async () => {
            problem.hidden = true;
            try {
              await api.savePrinter(printer?.id, {
                name: name.value.trim(), host: host.value.trim(), port: Number(port.value), paperWidth: width.value,
                categories: boxes.map((label) => label.querySelector('input')).filter((box) => box.checked).map((box) => box.value),
                enabled: enabled.checked,
              });
              toast({ title: t('settings.saved') });
              modal.close();
              loadPrinters();
            } catch (error) {
              problem.replaceChildren(icon('alert', 'sm'), errorMessage(error));
              problem.hidden = false;
            }
          } }, icon('check', 'sm'), t('common.save')),
        ],
      });
    }

    async function testPage(printer) {
      try {
        await api.testPrinter(printer.id);
        toast({ title: t('printing.testSent', { name: printer.name }) });
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    async function preview(printer) {
      try {
        const ticket = await api.previewTicket(printer.id);
        openModal({ title: t('printing.previewTitle', { name: printer.name }), size: 'sm',
          body: h('pre', { class: 'ticket-preview', style: { width: `${ticket.columns + 2}ch` } }, ticket.text),
          footer: (handle) => [h('button', { class: 'btn primary', onclick: handle.close }, t('common.close'))] });
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    async function loadPrinters() {
      if (!manager) return;
      try {
        printers = await api.printers();
      } catch (error) {
        printers = [];
        toast({ title: errorMessage(error), kind: 'error' });
      }
      renderPrinters();
    }

    async function loadTables() {
      if (!manager) return;
      try {
        tables = await api.tables();
      } catch (error) {
        tables = [];
        toast({ title: errorMessage(error), kind: 'error' });
      }
      renderQr();
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
    renderQr();
    loadTables();
    renderPrinters();
    loadPrinters();
    const offPrint = bus.on('print', () => loadPrinters());
    return { destroy: () => offPrint() };
  },
};
