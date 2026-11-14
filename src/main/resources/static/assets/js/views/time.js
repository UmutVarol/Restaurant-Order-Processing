// Shifts: everyone clocks in and out and sees their week, rota and tips; managers plan the rota, fix the time clock
// and share out a day's tips.
import { h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t, tn } from '../core/i18n.js';
import { dayLabel, money, time } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { confirmDialog, openModal, toast } from '../core/ui.js';

const pad = (n) => String(n).padStart(2, '0');
const isoDay = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const addDays = (day, n) => { const d = new Date(`${day}T12:00:00`); d.setDate(d.getDate() + n); return isoDay(d); };
const monday = (day) => { const d = new Date(`${day}T12:00:00`); d.setDate(d.getDate() - ((d.getDay() + 6) % 7)); return isoDay(d); };
const hours = (minutes) => t('time.hm', { h: Math.floor(minutes / 60), m: pad(minutes % 60) });
const localInput = (iso) => { const d = new Date(iso); return `${isoDay(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}`; };

export default {
  title: 'time.title',
  subtitle: 'time.sub',
  mount(container, { user }) {
    const manager = user.role === 'ADMIN';
    const tabs = manager ? ['me', 'rota', 'clock', 'tips'] : ['me', 'rota'];
    let tab = 'me';
    const switcher = h('div', { class: 'segmented time-tabs', role: 'tablist' });
    const body = h('div', { class: 'time-body' });
    mount(container, switcher, body);

    function renderTabs() {
      mount(switcher, tabs.map((id) => h('button', {
        class: tab === id ? 'active' : '', role: 'tab', 'aria-selected': String(tab === id), 'data-testid': `time-tab-${id}`,
        onclick: () => { tab = id; renderTabs(); show(); },
      }, t(`time.tab.${id}`))));
    }

    function show() {
      mount(body, h('div', { class: 'skeleton', style: { height: '220px', borderRadius: '20px' } }));
      ({ me: showMe, rota: showRota, clock: showClock, tips: showTips })[tab]();
    }

    const fail = (error) => toast({ title: errorMessage(error), kind: 'error' });
    const card = (title, sub, ...children) => h('section', { class: 'card time-card' },
      h('div', { class: 'card-head' }, h('div', null, h('div', { class: 'card-title' }, title), sub ? h('div', { class: 'card-sub' }, sub) : null)),
      h('div', { class: 'card-body' }, children));

    // ------------------------------------------------------------------ my time clock
    async function showMe() {
      let me;
      try { me = await api.myClock(); } catch (error) { fail(error); return; }
      const button = h('button', {
        class: ['btn', 'lg', 'block', me.clockedIn ? 'danger-solid' : 'primary'], 'data-testid': me.clockedIn ? 'clock-out' : 'clock-in',
        onclick: async () => {
          button.disabled = true;
          try {
            await (me.clockedIn ? api.clockOut() : api.clockIn());
            toast({ title: t(me.clockedIn ? 'time.outToast' : 'time.inToast') });
          } catch (error) { fail(error); }
          showMe();
        },
      }, icon('clock', 'sm'), t(me.clockedIn ? 'time.clockOut' : 'time.clockIn'));
      mount(body, h('div', { class: 'time-grid' },
        card(me.clockedIn ? t('time.since', { time: time(me.since) }) : t('time.notIn'), null,
          h('div', { class: 'time-totals' },
            h('div', null, h('small', { class: 'faint' }, t('time.today')), h('b', { 'data-testid': 'today-hours' }, hours(me.todayMinutes))),
            h('div', null, h('small', { class: 'faint' }, t('time.week')), h('b', null, hours(me.weekMinutes)))),
          button),
        card(t('time.upcoming'), null, me.upcoming.length
          ? h('ul', { class: 'time-list' }, me.upcoming.map((s) => h('li', null,
            h('b', null, `${dayLabel(isoDay(new Date(s.startsAt)), { weekday: 'short', day: 'numeric', month: 'short' })} · ${time(s.startsAt)}–${time(s.endsAt)}`),
            s.note ? h('small', { class: 'faint', dir: 'auto' }, s.note) : null)))
          : h('p', { class: 'muted' }, t('time.noShifts'))),
        card(t('time.myTips'), t('time.myTipsSub'), me.tips.length
          ? h('ul', { class: 'time-list' }, me.tips.map((tip) => h('li', null,
            h('span', null, dayLabel(tip.date, { weekday: 'short', day: 'numeric', month: 'short' })),
            h('b', null, money(tip.amount)), h('small', { class: 'faint' }, hours(tip.minutes)))))
          : h('p', { class: 'muted' }, t('time.noTips')))));
    }

    // ------------------------------------------------------------------ the rota
    let week = monday(isoDay(new Date()));
    async function showRota() {
      const to = addDays(week, 6);
      let shifts;
      let staff = [];
      try {
        [shifts, staff] = await Promise.all([api.shifts(week, to), manager ? api.staff() : Promise.resolve([])]);
      } catch (error) { fail(error); return; }
      const days = Array.from({ length: 7 }, (_, i) => addDays(week, i));
      mount(body,
        h('div', { class: 'time-toolbar' },
          h('button', { class: 'icon-btn sm', 'aria-label': t('time.prevWeek'), onclick: () => { week = addDays(week, -7); showRota(); } }, icon('chevronLeft', 'sm')),
          h('b', null, `${dayLabel(week, { day: 'numeric', month: 'short' })} – ${dayLabel(to, { day: 'numeric', month: 'short' })}`),
          h('button', { class: 'icon-btn sm', 'aria-label': t('time.nextWeek'), onclick: () => { week = addDays(week, 7); showRota(); } }, icon('chevronRight', 'sm')),
          manager ? h('button', { class: 'btn sm primary', 'data-testid': 'shift-add', onclick: () => editShift(null, staff, days.includes(isoDay(new Date())) ? isoDay(new Date()) : days[0]) }, icon('plus', 'sm'), h('span', null, t('time.addShift'))) : null),
        h('div', { class: 'rota' }, days.map((day) => {
          const today = shifts.filter((s) => isoDay(new Date(s.startsAt)) === day);
          return h('div', { class: 'rota-day' },
            h('div', { class: 'rota-head' }, dayLabel(day, { weekday: 'long', day: 'numeric', month: 'short' })),
            today.length ? today.map((s) => h(manager ? 'button' : 'div', {
              class: ['rota-shift', s.username === user.username ? 'mine' : ''], type: manager ? 'button' : undefined, 'data-testid': `shift-${s.id}`,
              onclick: manager ? () => editShift(s, staff, day) : undefined,
            }, h('b', { dir: 'auto' }, s.displayName), h('span', null, `${time(s.startsAt)}–${time(s.endsAt)}`),
            s.note ? h('small', { class: 'faint', dir: 'auto' }, s.note) : null))
              : h('p', { class: 'faint rota-empty' }, '—'),
            manager ? h('button', { class: 'btn sm ghost rota-add', onclick: () => editShift(null, staff, day) }, icon('plus', 'sm'), t('time.addShift')) : null);
        })));
    }

    function editShift(shift, staff, day) {
      const people = staff.filter((s) => s.enabled);
      const who = h('select', { class: 'select', id: 'shift-user' }, people.map((p) => h('option', { value: p.id, selected: shift?.userId === p.id }, `${p.displayName} · ${t(`role.${p.role}`)}`)));
      const date = h('input', { class: 'input', id: 'shift-date', type: 'date', value: shift ? isoDay(new Date(shift.startsAt)) : day });
      const from = h('input', { class: 'input', id: 'shift-from', type: 'time', step: 900, value: shift ? localInput(shift.startsAt).slice(11) : '17:00' });
      const to = h('input', { class: 'input', id: 'shift-to', type: 'time', step: 900, value: shift ? localInput(shift.endsAt).slice(11) : '23:00' });
      const note = h('input', { class: 'input', id: 'shift-note', maxlength: 80, value: shift?.note ?? '', placeholder: t('time.notePlaceholder') });
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      const save = h('button', { class: 'btn primary', 'data-testid': 'shift-save' }, icon('check', 'sm'), t('common.save'));
      const field = (id, label, control) => h('div', { class: 'field' }, h('label', { for: id }, label), control);
      const modal = openModal({
        title: shift ? t('time.editShift') : t('time.addShift'),
        size: 'sm',
        body: [field('shift-user', t('time.who'), who), field('shift-date', t('resv.date'), date),
          h('div', { class: 'grid-2' }, field('shift-from', t('time.from'), from), field('shift-to', t('time.to'), to)),
          field('shift-note', t('resv.note'), note),
          shift ? h('button', { class: 'btn sm ghost danger-text', 'data-testid': 'shift-remove', onclick: () => remove() }, icon('trash', 'sm'), t('time.removeShift')) : null,
          problem],
        footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), save],
      });
      save.addEventListener('click', async () => {
        const start = new Date(`${date.value}T${from.value}`);
        const end = new Date(`${date.value}T${to.value}`);
        if (end <= start) end.setDate(end.getDate() + 1); // a shift past midnight
        const request = { userId: Number(who.value), startsAt: start.toISOString(), endsAt: end.toISOString(), note: note.value || null };
        save.disabled = true;
        try {
          await (shift ? api.updateShift(shift.id, request) : api.addShift(request));
          modal.close();
          showRota();
        } catch (error) {
          problem.replaceChildren(icon('alert', 'sm'), errorMessage(error));
          problem.hidden = false;
          save.disabled = false;
        }
      });
      async function remove() {
        try { await api.removeShift(shift.id); modal.close(); showRota(); } catch (error) { fail(error); }
      }
    }

    // ------------------------------------------------------------------ time clock (managers)
    let clockFrom = monday(isoDay(new Date()));
    async function showClock() {
      const clockTo = addDays(clockFrom, 6);
      let entries;
      let report;
      try { [entries, report] = await Promise.all([api.timeEntries(clockFrom, clockTo), api.hoursReport(clockFrom, clockTo)]); } catch (error) { fail(error); return; }
      mount(body,
        h('div', { class: 'time-toolbar' },
          h('button', { class: 'icon-btn sm', 'aria-label': t('time.prevWeek'), onclick: () => { clockFrom = addDays(clockFrom, -7); showClock(); } }, icon('chevronLeft', 'sm')),
          h('b', null, `${dayLabel(clockFrom, { day: 'numeric', month: 'short' })} – ${dayLabel(clockTo, { day: 'numeric', month: 'short' })}`),
          h('button', { class: 'icon-btn sm', 'aria-label': t('time.nextWeek'), onclick: () => { clockFrom = addDays(clockFrom, 7); showClock(); } }, icon('chevronRight', 'sm'))),
        h('div', { class: 'time-grid' },
          card(t('time.hours'), t('time.hoursSub'), report.length
            ? h('ul', { class: 'time-list' }, report.map((row) => h('li', { 'data-testid': `hours-${row.username}` },
              h('span', { dir: 'auto' }, row.displayName, row.clockedInNow ? h('span', { class: 'pill plain time-on' }, t('time.onNow')) : null),
              h('b', null, hours(row.minutes)), h('small', { class: 'faint' }, tn('time.entries', row.entries)))))
            : h('p', { class: 'muted' }, t('time.noEntries'))),
          card(t('time.entriesTitle'), null, entries.length
            ? h('ul', { class: 'time-list' }, entries.map((e) => h('li', { class: e.forgotten ? 'forgotten' : '', 'data-testid': `entry-${e.id}` },
              h('span', { dir: 'auto' }, h('b', null, e.displayName), h('small', { class: 'faint' },
                ` ${dayLabel(isoDay(new Date(e.clockedInAt)), { weekday: 'short', day: 'numeric' })} ${time(e.clockedInAt)}–${e.clockedOutAt ? time(e.clockedOutAt) : '…'}`),
              e.forgotten ? h('span', { class: 'pill r-LATE' }, t('time.forgotten')) : null,
              e.correctionReason ? h('small', { class: 'faint time-reason', dir: 'auto' }, `${t('time.corrected')}: ${e.correctionReason}`) : null),
              h('b', null, hours(e.minutes)),
              h('span', { class: 'time-actions' },
                !e.clockedOutAt ? h('button', { class: 'btn sm ghost', 'data-testid': `entry-out-${e.id}`, onclick: () => clockOutFor(e) }, t('time.clockOut')) : null,
                h('button', { class: 'icon-btn sm', title: t('time.correct'), 'aria-label': t('time.correct'), 'data-testid': `entry-fix-${e.id}`, onclick: () => correct(e) }, icon('edit', 'sm'))))))
            : h('p', { class: 'muted' }, t('time.noEntries')))));
    }

    async function clockOutFor(entry) {
      if (!(await confirmDialog({ title: t('time.clockOutFor', { name: entry.displayName }), confirmLabel: t('time.clockOut') }))) return;
      try { await api.clockOutFor(entry.userId); showClock(); } catch (error) { fail(error); }
    }

    function correct(entry) {
      const from = h('input', { class: 'input', id: 'fix-from', type: 'datetime-local', value: localInput(entry.clockedInAt) });
      const to = h('input', { class: 'input', id: 'fix-to', type: 'datetime-local', value: entry.clockedOutAt ? localInput(entry.clockedOutAt) : '' });
      const reason = h('input', { class: 'input', id: 'fix-reason', maxlength: 140, placeholder: t('time.reasonPlaceholder') });
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      const save = h('button', { class: 'btn primary', 'data-testid': 'fix-save' }, icon('check', 'sm'), t('common.save'));
      const field = (id, label, control) => h('div', { class: 'field' }, h('label', { for: id }, label), control);
      const modal = openModal({
        title: t('time.correctTitle', { name: entry.displayName }),
        size: 'sm',
        body: [field('fix-from', t('time.in'), from), field('fix-to', t('time.out'), to), field('fix-reason', t('time.reason'), reason), problem],
        footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), save],
      });
      save.addEventListener('click', async () => {
        save.disabled = true;
        try {
          await api.correctEntry(entry.id, {
            clockedInAt: new Date(from.value).toISOString(), clockedOutAt: to.value ? new Date(to.value).toISOString() : null, reason: reason.value,
          });
          modal.close();
          showClock();
        } catch (error) {
          problem.replaceChildren(icon('alert', 'sm'), errorMessage(error));
          problem.hidden = false;
          save.disabled = false;
        }
      });
    }

    // ------------------------------------------------------------------ tips (managers)
    let tipDay = isoDay(new Date());
    async function showTips() {
      let pool;
      try { pool = await api.tipPool(tipDay); } catch (error) { fail(error); return; }
      const close = h('button', { class: 'btn primary', 'data-testid': 'tips-close', disabled: pool.closed || pool.stillClockedIn.length > 0, onclick: closeDay }, icon('check', 'sm'), t('time.closeTips'));
      mount(body,
        h('div', { class: 'time-toolbar' },
          h('button', { class: 'icon-btn sm', 'aria-label': t('resv.prevDay'), onclick: () => { tipDay = addDays(tipDay, -1); showTips(); } }, icon('chevronLeft', 'sm')),
          h('b', { 'data-testid': 'tips-day' }, dayLabel(tipDay, { weekday: 'long', day: 'numeric', month: 'short' })),
          h('button', { class: 'icon-btn sm', 'aria-label': t('resv.nextDay'), onclick: () => { tipDay = addDays(tipDay, 1); showTips(); } }, icon('chevronRight', 'sm'))),
        card(t('time.tipsTotal', { total: money(pool.tipsTotal) }), pool.closed ? t('time.tipsClosed', { name: pool.closedBy }) : t('time.tipsPreview'),
          pool.stillClockedIn.length ? h('p', { class: 'form-error' }, icon('alert', 'sm'), t('time.stillIn', { names: pool.stillClockedIn.join(', ') })) : null,
          pool.shares.length
            ? h('ul', { class: 'time-list' }, pool.shares.map((s) => h('li', { 'data-testid': `share-${s.userId}` },
              h('span', { dir: 'auto' }, s.displayName, h('small', { class: 'faint' }, ` · ${t(`role.${s.role}`)} ×${Number(s.weight)}`)),
              h('small', { class: 'faint' }, hours(s.minutes)), h('b', null, money(s.amount)))))
            : h('p', { class: 'muted' }, t('time.nobodyWorked')),
          h('p', { class: 'faint time-hint' }, t('time.tipsHow')),
          pool.closed ? null : close));

      async function closeDay() {
        if (!(await confirmDialog({ title: t('time.closeTipsTitle'), text: t('time.closeTipsText'), confirmLabel: t('time.closeTips') }))) return;
        try { await api.closeTipPool(tipDay); toast({ title: t('time.tipsClosedToast') }); showTips(); } catch (error) { fail(error); }
      }
    }

    renderTabs();
    show();
    return {};
  },
};
