// Setup, for managers: what is left to do before the first evening, the staff accounts, and the dining room's
// tables. A new restaurant starts here.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t, tn } from '../core/i18n.js';
import { api, errorMessage } from '../core/api.js';
import { confirmDialog, openModal, toast } from '../core/ui.js';
import { withRecentSignIn } from './reauth.js';

const ROLES = ['WAITER', 'KITCHEN', 'ADMIN'];
const ZONES = ['INDOOR', 'WINDOW', 'TERRACE'];

export default {
  title: 'setup.title',
  subtitle: 'setup.sub',
  mount(container, { user, navigate }) {
    let staff = null;
    let tables = null;
    let facts = null;
    const checklistCard = h('section', { class: 'card setup-card', 'data-testid': 'setup-checklist' });
    const staffCard = h('section', { class: 'card setup-card', 'data-testid': 'setup-staff' });
    const tablesCard = h('section', { class: 'card setup-card', 'data-testid': 'setup-tables' });
    mount(container, h('div', { class: 'setup-grid' }, checklistCard, staffCard, tablesCard));

    const head = (iconName, title, sub, action) => h('div', { class: 'card-head' },
      h('span', { class: 'settings-ic' }, icon(iconName, 'sm')),
      h('div', { style: { flex: 1, minWidth: 0 } }, h('div', { class: 'card-title' }, title), h('div', { class: 'card-sub' }, sub)),
      action ?? null);
    const skeleton = () => h('div', { class: 'card-body' }, h('div', { class: 'skeleton', style: { height: '120px' } }));

    // ------------------------------------------------------------------ checklist
    function renderChecklist() {
      if (!staff || !tables || !facts) {
        mount(checklistCard, head('check', t('setup.checklist'), t('setup.checklistSub')), skeleton());
        return;
      }
      const steps = [
        { id: 'password', done: !user.mustChangePassword, label: t('setup.step.password') },
        { id: 'totp', done: user.totpEnabled, label: t('setup.step.totp'), go: () => navigate('settings') },
        { id: 'staff', done: staff.filter((s) => s.enabled).length > 1, label: t('setup.step.staff'), go: () => addStaff() },
        // a new installation comes with a sample menu and twelve sample tables: done once the manager has been through them
        { id: 'tables', done: tables.length > 0 && reviewed('tables'), label: t('setup.step.tables'), go: () => { markReviewed('tables'); renderChecklist(); tablesCard.scrollIntoView({ behavior: 'smooth' }); } },
        { id: 'menu', done: facts.menuItems > 0 && reviewed('menu'), label: t('setup.step.menu'), go: () => { markReviewed('menu'); navigate('menu'); } },
        { id: 'qr', done: tables.length > 0 && tables.every((x) => x.qrIssuedAt), label: t('setup.step.qr'), go: () => navigate('settings') },
        { id: 'printers', done: facts.printers > 0, label: t('setup.step.printers'), optional: true, go: () => navigate('settings') },
      ];
      const done = steps.filter((s) => s.done).length;
      mount(checklistCard,
        head('check', t('setup.checklist'), t('setup.progress', { done, total: steps.length })),
        h('div', { class: 'card-body' },
          h('div', { class: 'setup-bar', role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': steps.length, 'aria-valuenow': done },
            h('span', { style: { width: `${(done / steps.length) * 100}%` } })),
          h('ol', { class: 'setup-steps' }, steps.map((step) => h('li', { class: step.done ? 'done' : '', 'data-testid': `step-${step.id}` },
            h('span', { class: 'setup-tick', 'aria-hidden': 'true' }, step.done ? icon('check', 'sm') : null),
            h('span', { class: 'setup-label' }, step.label, step.optional ? h('small', { class: 'faint' }, ` · ${t('setup.optional')}`) : null),
            !step.done && step.go ? h('button', { class: 'btn sm ghost', onclick: step.go }, t('setup.do'), icon('chevronRight', 'sm')) : null)))));
    }

    // per device: a convenience, not a record (losing it only shows the step again)
    function reviewed(step) {
      try { return localStorage.getItem(`sofra.setup.${step}`) === '1'; } catch { return false; }
    }
    function markReviewed(step) {
      try { localStorage.setItem(`sofra.setup.${step}`, '1'); } catch { /* storage blocked */ }
    }

    // ------------------------------------------------------------------ staff
    function renderStaff() {
      mount(staffCard,
        head('users', t('setup.staff'), staff ? tn('setup.staffCount', staff.filter((s) => s.enabled).length) : t('setup.staffSub'),
          h('button', { class: 'btn sm primary', 'data-testid': 'staff-add', onclick: () => addStaff() }, icon('plus', 'sm'), h('span', null, t('setup.addStaff')))),
        staff == null ? skeleton()
          : h('div', { class: 'card-body setup-list' }, staff.map((member) => h('div', { class: ['setup-row', member.enabled ? '' : 'muted-row'], 'data-testid': `staff-${member.username}` },
            h('div', { class: 'setup-main' },
              h('b', { dir: 'auto' }, member.displayName),
              h('small', { class: 'faint' }, member.username, ' · ', t(`role.${member.role}`),
                member.username === user.username ? ` · ${t('setup.you')}` : '',
                !member.enabled ? ` · ${t('setup.disabled')}` : member.mustChangePassword ? ` · ${t('setup.awaitingFirstSignIn')}` : '')),
            h('div', { class: 'setup-actions' },
              member.totpEnabled ? h('span', { class: 'pill plain', title: t('setup.totpOn') }, icon('lock', 'sm')) : null,
              h('button', { class: 'icon-btn sm', title: t('common.edit'), 'aria-label': t('common.edit'), 'data-testid': `staff-edit-${member.username}`, onclick: () => editStaff(member) }, icon('edit', 'sm')))))));
    }

    function addStaff() {
      const username = h('input', { class: 'input', id: 'staff-username', maxlength: 50, autocomplete: 'off', autocapitalize: 'none', spellcheck: false, placeholder: 'ayse' });
      const name = h('input', { class: 'input', id: 'staff-name', maxlength: 80, autocomplete: 'off' });
      const role = h('select', { class: 'select', id: 'staff-role' }, ROLES.map((r) => h('option', { value: r }, t(`role.${r}`))));
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      const save = h('button', { class: 'btn primary', 'data-testid': 'staff-save' }, icon('check', 'sm'), t('setup.create'));
      // a username from the name, the way people write it on a till: "Ayşe Kaya" -> "ayse.kaya"
      name.addEventListener('input', () => {
        if (username.dataset.touched) return;
        username.value = name.value.toLocaleLowerCase('tr').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/ı/g, 'i')
          .trim().replace(/\s+/g, '.').replace(/[^a-z0-9._-]/g, '').slice(0, 50);
      });
      username.addEventListener('input', () => { username.dataset.touched = '1'; });
      const modal = openModal({
        title: t('setup.addStaff'),
        size: 'sm',
        body: [field('staff-name', t('setup.name'), name), field('staff-username', t('login.username'), username, t('setup.usernameHint')),
          field('staff-role', t('setup.role'), role, t('setup.roleHint')), problem],
        footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), save],
      });
      name.focus();
      save.addEventListener('click', async () => {
        save.disabled = true;
        problem.hidden = true;
        try {
          const result = await withRecentSignIn(() => api.addStaff({ username: username.value.trim(), displayName: name.value.trim(), role: role.value }));
          if (!result) { save.disabled = false; return; }
          modal.close();
          showCredentials(result, 'setup.createdTitle');
          loadStaff();
        } catch (error) {
          problem.replaceChildren(icon('alert', 'sm'), errorMessage(error));
          problem.hidden = false;
          save.disabled = false;
        }
      });
    }

    function editStaff(member) {
      const self = member.username === user.username;
      const name = h('input', { class: 'input', id: 'staff-name', maxlength: 80, value: member.displayName });
      const role = h('select', { class: 'select', id: 'staff-role', disabled: self }, ROLES.map((r) => h('option', { value: r, selected: member.role === r }, t(`role.${r}`))));
      const enabled = h('input', { type: 'checkbox', checked: member.enabled, disabled: self, 'data-testid': 'staff-enabled' });
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      const save = h('button', { class: 'btn primary', 'data-testid': 'staff-save' }, icon('check', 'sm'), t('common.save'));
      const fail = (error) => { problem.replaceChildren(icon('alert', 'sm'), errorMessage(error)); problem.hidden = false; };
      const modal = openModal({
        title: member.displayName,
        size: 'sm',
        body: [
          h('p', { class: 'muted' }, member.username),
          field('staff-name', t('setup.name'), name),
          field('staff-role', t('setup.role'), role, self ? t('setup.selfHint') : t('setup.roleChangeHint')),
          h('label', { class: 'checkbox' }, enabled, t('setup.canSignIn')),
          self ? null : h('div', { class: 'setup-more' },
            h('button', { class: 'btn sm ghost', 'data-testid': 'staff-reset', onclick: () => reset() }, icon('refresh', 'sm'), t('setup.resetPassword')),
            member.hasPin ? h('button', { class: 'btn sm ghost', onclick: () => clearPin() }, icon('x', 'sm'), t('setup.clearPin')) : null),
          problem,
        ],
        footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), save],
      });
      save.addEventListener('click', async () => {
        save.disabled = true;
        try {
          const updated = await withRecentSignIn(() => api.updateStaff(member.id, { displayName: name.value.trim(), role: role.value, enabled: enabled.checked }));
          if (!updated) { save.disabled = false; return; }
          modal.close();
          toast({ title: t('setup.savedStaff', { name: updated.displayName }) });
          loadStaff();
        } catch (error) {
          fail(error);
          save.disabled = false;
        }
      });

      async function reset() {
        if (!(await confirmDialog({ title: t('setup.resetTitle', { name: member.displayName }), text: t('setup.resetText'), confirmLabel: t('setup.resetPassword'), danger: true }))) return;
        try {
          const result = await withRecentSignIn(() => api.resetStaffPassword(member.id));
          if (!result) return;
          modal.close();
          showCredentials(result, 'setup.resetDoneTitle');
          loadStaff();
        } catch (error) {
          fail(error);
        }
      }

      async function clearPin() {
        try {
          await api.clearStaffPin(member.id);
          modal.close();
          toast({ title: t('setup.pinCleared', { name: member.displayName }) });
          loadStaff();
        } catch (error) {
          fail(error);
        }
      }
    }

    /** The temporary password is shown once, to read out or write down for the employee. */
    function showCredentials({ staff: member, temporaryPassword }, titleKey) {
      openModal({
        title: t(titleKey, { name: member.displayName }),
        size: 'sm',
        body: [
          h('p', { class: 'muted' }, t('setup.credentialsText')),
          h('dl', { class: 'setup-credentials' },
            h('dt', null, t('login.username')), h('dd', null, h('code', { 'data-testid': 'cred-username' }, member.username)),
            h('dt', null, t('setup.temporaryPassword')), h('dd', null, h('code', { 'data-testid': 'cred-password' }, temporaryPassword))),
          h('p', { class: 'faint' }, t('setup.credentialsOnce')),
        ],
        footer: (handle) => [h('button', { class: 'btn primary', onclick: handle.close }, t('common.done'))],
      });
    }

    // ------------------------------------------------------------------ tables
    function renderTables() {
      mount(tablesCard,
        head('table', t('setup.tables'), tables ? tn('setup.tableCount', tables.length) : t('setup.tablesSub'),
          h('button', { class: 'btn sm primary', 'data-testid': 'table-add', onclick: () => editTable(null) }, icon('plus', 'sm'), h('span', null, t('setup.addTable')))),
        tables == null ? skeleton()
          : !tables.length ? h('div', { class: 'card-body' }, h('p', { class: 'muted' }, t('setup.noTables')))
            : h('div', { class: 'card-body setup-tables' }, tables.map((table) => h('button', {
              type: 'button', class: 'setup-table', 'data-testid': `table-${table.code}`, onclick: () => editTable(table),
            }, h('b', null, table.code), h('small', null, tn('floor.seats', table.seats)), h('small', { class: 'faint' }, t(`zone.${table.zone}`))))));
    }

    function editTable(table) {
      const used = new Set((tables ?? []).map((x) => x.code));
      let suggestion = 1;
      while (used.has(String(suggestion))) suggestion++;
      const code = h('input', { class: 'input', id: 'table-code', maxlength: 10, value: table?.code ?? String(suggestion), autocomplete: 'off' });
      const seats = h('input', { class: 'input', id: 'table-seats', type: 'number', min: 1, max: 30, inputmode: 'numeric', value: table?.seats ?? 4 });
      const zone = h('select', { class: 'select', id: 'table-zone' }, ZONES.map((z) => h('option', { value: z, selected: (table?.zone ?? 'INDOOR') === z }, t(`zone.${z}`))));
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      const save = h('button', { class: 'btn primary', 'data-testid': 'table-save' }, icon('check', 'sm'), t('common.save'));
      const fail = (error) => { problem.replaceChildren(icon('alert', 'sm'), errorMessage(error)); problem.hidden = false; };
      const modal = openModal({
        title: table ? t('setup.editTable', { table: table.code }) : t('setup.addTable'),
        size: 'sm',
        body: [
          h('div', { class: 'grid-2' }, field('table-code', t('setup.tableNumber'), code), field('table-seats', t('setup.seats'), seats)),
          field('table-zone', t('setup.zone'), zone),
          table ? h('button', { class: 'btn sm ghost danger-text', 'data-testid': 'table-remove', onclick: () => remove() }, icon('trash', 'sm'), t('setup.removeTable')) : null,
          problem,
        ],
        footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), save],
      });
      code.focus();
      code.select();
      save.addEventListener('click', async () => {
        save.disabled = true;
        const body = { code: code.value.trim(), seats: Number(seats.value), zone: zone.value };
        try {
          await (table ? api.updateTable(table.id, body) : api.addTable(body));
          modal.close();
          toast({ title: t('setup.savedTable', { table: body.code }) });
          loadTables();
        } catch (error) {
          fail(error);
          save.disabled = false;
        }
      });

      async function remove() {
        if (!(await confirmDialog({ title: t('setup.removeTitle', { table: table.code }), text: t('setup.removeText'), confirmLabel: t('setup.removeTable'), danger: true }))) return;
        try {
          await api.removeTable(table.id);
          modal.close();
          toast({ title: t('setup.removedTable', { table: table.code }) });
          loadTables();
        } catch (error) {
          fail(error);
        }
      }
    }

    function field(id, label, control, hint) {
      return h('div', { class: 'field' }, h('label', { for: id }, label), control, hint ? h('small', { class: 'faint' }, hint) : null);
    }

    async function loadStaff() {
      try { staff = await api.staff(); } catch (error) { staff = []; toast({ title: errorMessage(error), kind: 'error' }); }
      renderStaff();
      renderChecklist();
    }

    async function loadTables() {
      try { tables = await api.diningTables(); } catch (error) { tables = []; toast({ title: errorMessage(error), kind: 'error' }); }
      renderTables();
      renderChecklist();
    }

    async function loadFacts() {
      const [menu, printers] = await Promise.allSettled([api.menu(), api.printers()]);
      facts = {
        menuItems: menu.status === 'fulfilled' ? menu.value.length : 0,
        printers: printers.status === 'fulfilled' ? printers.value.length : 0,
      };
      renderChecklist();
    }

    renderChecklist();
    renderStaff();
    renderTables();
    loadStaff();
    loadTables();
    loadFacts();
    return {};
  },
};
