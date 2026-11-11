// Reservations: the day's bookings, the walk-in waitlist and the parties at their tables, updated live.
import { debounce, h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t, tn } from '../core/i18n.js';
import { dayLabel, minutesSince, time } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { confirmDialog, openModal, toast } from '../core/ui.js';

const DURATIONS = [60, 90, 120, 150, 180];

/** YYYY-MM-DD in the device's time zone, which is the restaurant's. */
function isoDay(date) {
  const pad = (n) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function shiftDay(day, by) {
  const date = new Date(`${day}T12:00:00`);
  date.setDate(date.getDate() + by);
  return isoDay(date);
}

export default {
  title: 'resv.title',
  subtitle: 'resv.sub',
  mount(container) {
    let day = isoDay(new Date());
    let parties = null;

    const dayText = h('span', { class: 'resv-day', 'data-testid': 'resv-day' });
    // in the page rather than the top bar, where a phone has no room for a day switcher and two buttons
    const toolbar = h('div', { class: 'resv-toolbar' },
      h('div', { class: 'resv-days' },
        h('button', { class: 'icon-btn sm', 'aria-label': t('resv.prevDay'), title: t('resv.prevDay'), onclick: () => go(-1) }, icon('chevronLeft', 'sm')),
        dayText,
        h('button', { class: 'icon-btn sm', 'aria-label': t('resv.nextDay'), title: t('resv.nextDay'), onclick: () => go(1) }, icon('chevronRight', 'sm'))),
      h('div', { class: 'resv-adds' },
        h('button', { class: 'btn sm ghost', 'data-testid': 'resv-walk-in', onclick: () => walkInForm() }, icon('users', 'sm'), h('span', null, t('resv.addWalkIn'))),
        h('button', { class: 'btn sm primary', 'data-testid': 'resv-book', onclick: () => bookingForm(null) }, icon('plus', 'sm'), h('span', null, t('resv.addBooking')))));
    const board = h('div');
    mount(container, toolbar, board);

    function go(by) {
      day = shiftDay(day, by);
      parties = null;
      render();
      load();
    }

    function render() {
      const today = isoDay(new Date());
      dayText.textContent = day === today ? t('resv.today') : dayLabel(day, { weekday: 'short', day: 'numeric', month: 'short' });
      if (!parties) {
        mount(board, h('div', { class: 'resv-grid' }, [1, 2].map(() => h('div', { class: 'skeleton', style: { height: '260px', borderRadius: '20px' } }))));
        return;
      }
      const waiting = parties.filter((p) => p.status === 'WAITING').sort((a, b) => a.createdAt.localeCompare(b.createdAt));
      const seated = parties.filter((p) => p.status === 'SEATED');
      const bookings = parties.filter((p) => p.kind === 'BOOKING' && p.status !== 'SEATED');
      const covers = bookings.filter((p) => p.status === 'BOOKED').reduce((n, p) => n + p.partySize, 0);

      mount(board, h('div', { class: 'resv-grid' },
        section('resv-waitlist', t('resv.waitlist'), tn('resv.parties', waiting.length),
          waiting.length ? waiting.map((p, i) => waitingRow(p, i + 1)) : empty(t('resv.noneWaiting'))),
        section('resv-bookings', t('resv.bookings'), tn('resv.covers', covers),
          bookings.length ? bookings.map(bookingRow) : empty(t('resv.noneBooked'))),
        seated.length ? section('resv-seated', t('resv.seated'), tn('resv.parties', seated.length), seated.map(seatedRow)) : null));
    }

    function section(testid, title, sub, rows) {
      return h('section', { class: 'card resv-section', 'data-testid': testid },
        h('div', { class: 'card-head' }, h('div', null, h('div', { class: 'card-title' }, title), h('div', { class: 'card-sub' }, sub))),
        h('div', { class: 'card-body resv-list' }, rows));
    }

    const empty = (text) => h('p', { class: 'muted resv-empty' }, text);

    function who(p) {
      return h('div', { class: 'resv-who' },
        h('div', { class: 'resv-name', dir: 'auto' }, p.guestName),
        h('div', { class: 'resv-meta' },
          h('span', null, icon('users', 'sm'), tn('resv.people', p.partySize)),
          p.tableCode ? h('span', null, icon('table', 'sm'), t('resv.table', { table: p.tableCode })) : null,
          p.phone ? h('a', { href: `tel:${p.phone.replace(/[^+0-9]/g, '')}`, class: 'resv-phone' }, icon('phone', 'sm'), p.phone) : null),
        p.note ? h('div', { class: 'resv-note faint', dir: 'auto' }, p.note) : null);
    }

    function waitingRow(p, position) {
      const waited = minutesSince(p.createdAt);
      const over = p.quotedMinutes != null && waited > p.quotedMinutes;
      return h('div', { class: 'resv-row', 'data-testid': `resv-${p.id}` },
        h('div', { class: 'resv-time' }, h('strong', null, `#${position}`),
          h('small', { class: over ? 'resv-over' : 'faint' }, tn('resv.minutes', waited))),
        who(p),
        h('div', { class: 'resv-actions' },
          p.quotedMinutes != null ? h('span', { class: 'pill plain' }, t('resv.quoted', { n: p.quotedMinutes })) : null,
          h('button', { class: 'btn sm primary', 'data-testid': `seat-${p.id}`, onclick: () => seat(p) }, t('resv.seat')),
          h('button', { class: 'icon-btn sm', title: t('resv.remove'), 'aria-label': t('resv.remove'), onclick: () => close(p, 'cancel') }, icon('x', 'sm'))));
    }

    function bookingRow(p) {
      const late = p.status === 'BOOKED' && minutesSince(p.startsAt) > 0;
      return h('div', { class: ['resv-row', p.status !== 'BOOKED' ? 'closed' : ''], 'data-testid': `resv-${p.id}` },
        h('div', { class: 'resv-time' }, h('strong', null, time(p.startsAt)),
          h('small', { class: 'faint' }, tn('resv.minutes', p.durationMinutes))),
        who(p),
        h('div', { class: 'resv-actions' },
          p.status === 'BOOKED'
            ? [
              late ? h('span', { class: 'pill r-LATE' }, t('resv.late')) : null,
              h('button', { class: 'btn sm primary', 'data-testid': `seat-${p.id}`, onclick: () => seat(p) }, t('resv.arrived')),
              h('button', { class: 'icon-btn sm', title: t('common.edit'), 'aria-label': t('common.edit'), onclick: () => bookingForm(p) }, icon('edit', 'sm')),
              h('button', { class: 'icon-btn sm', title: t('resv.noShow'), 'aria-label': t('resv.noShow'), 'data-testid': `no-show-${p.id}`, onclick: () => close(p, 'no-show') }, icon('clock', 'sm')),
              h('button', { class: 'icon-btn sm', title: t('resv.cancel'), 'aria-label': t('resv.cancel'), onclick: () => close(p, 'cancel') }, icon('x', 'sm')),
            ]
            : h('span', { class: `pill r-${p.status}` }, t(`resv.status.${p.status}`))));
    }

    function seatedRow(p) {
      return h('div', { class: 'resv-row', 'data-testid': `resv-${p.id}` },
        h('div', { class: 'resv-time' }, h('strong', null, p.tableCode),
          h('small', { class: 'faint' }, tn('resv.minutes', minutesSince(p.seatedAt)))),
        who(p),
        h('div', { class: 'resv-actions' },
          h('button', { class: 'btn sm ghost', 'data-testid': `finish-${p.id}`, onclick: () => close(p, 'finish') }, t('resv.left'))));
    }

    async function close(p, how) {
      const ok = await confirmDialog({
        title: t(`resv.confirm.${how}`, { name: p.guestName }),
        confirmLabel: t(`resv.do.${how}`),
        danger: how !== 'finish',
      });
      if (!ok) return;
      try {
        if (how === 'cancel') await api.cancelReservation(p.id);
        else if (how === 'no-show') await api.noShow(p.id);
        else await api.finishReservation(p.id);
        reload();
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    /** Pick a free table now; a table booked soon by someone else needs a second, deliberate tap. */
    async function seat(p) {
      let options = [];
      try {
        options = await api.freeTables({ startsAt: new Date().toISOString(), partySize: p.partySize, durationMinutes: p.durationMinutes, except: p.id });
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
        return;
      }
      if (p.tableId && !options.some((o) => o.id === p.tableId)) {
        options = [{ id: p.tableId, code: p.tableCode, seats: null }, ...options];
      }
      let picked = options.find((o) => o.id === p.tableId) || null;
      let override = false;
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      const confirm = h('button', { class: 'btn primary', disabled: !picked, 'data-testid': 'seat-confirm' }, icon('check', 'sm'), t('resv.seat'));
      const list = h('div', { class: 'table-picker' });
      const renderList = () => mount(list, options.length
        ? options.map((o) => h('button', {
          type: 'button', class: picked === o ? 'active' : '', 'aria-pressed': String(picked === o), 'data-testid': `seat-table-${o.code}`,
          onclick: () => { picked = o; override = false; problem.hidden = true; confirm.disabled = false; confirm.textContent = t('resv.seat'); renderList(); },
        }, o.code, o.seats ? h('small', null, tn('floor.seats', o.seats)) : null))
        : h('p', { class: 'muted' }, t('resv.noFreeTable')));
      renderList();
      const modal = openModal({
        title: t('resv.seatTitle', { name: p.guestName }),
        size: 'sm',
        body: [h('p', { class: 'muted' }, tn('resv.people', p.partySize)), list, problem],
        footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), confirm],
      });
      confirm.addEventListener('click', async () => {
        confirm.disabled = true;
        try {
          await api.seatParty(p.id, picked.id, override);
          toast({ title: t('resv.seatedToast', { name: p.guestName, table: picked.code }) });
          modal.close();
          reload();
        } catch (error) {
          problem.replaceChildren(icon('alert', 'sm'), errorMessage(error));
          problem.hidden = false;
          confirm.disabled = false;
          if (error.code === 'TABLE_BOOKED_SOON') {
            override = true;
            confirm.textContent = t('resv.seatAnyway');
          }
        }
      });
    }

    const field = (id, label, control) => h('div', { class: 'field' }, h('label', { for: id }, label), control);

    function walkInForm() {
      const name = h('input', { class: 'input', id: 'walkin-name', maxlength: 60, autocomplete: 'off' });
      const size = h('input', { class: 'input', id: 'walkin-size', type: 'number', min: 1, max: 30, value: 2, inputmode: 'numeric' });
      const quoted = h('input', { class: 'input', id: 'walkin-quoted', type: 'number', min: 0, max: 240, value: 15, inputmode: 'numeric' });
      const phone = h('input', { class: 'input', id: 'walkin-phone', type: 'tel', maxlength: 20, autocomplete: 'off' });
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      const save = h('button', { class: 'btn primary', 'data-testid': 'walkin-save' }, icon('check', 'sm'), t('resv.addToList'));
      const modal = openModal({
        title: t('resv.addWalkIn'),
        size: 'sm',
        body: [field('walkin-name', t('resv.name'), name),
          h('div', { class: 'grid-2' }, field('walkin-size', t('resv.partySize'), size), field('walkin-quoted', t('resv.quotedLabel'), quoted)),
          field('walkin-phone', t('resv.phoneOptional'), phone), problem],
        footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), save],
      });
      name.focus();
      save.addEventListener('click', async () => {
        save.disabled = true;
        try {
          await api.addWalkIn({ guestName: name.value, partySize: Number(size.value), quotedMinutes: quoted.value === '' ? null : Number(quoted.value), phone: phone.value || null });
          modal.close();
          reload();
        } catch (error) {
          problem.replaceChildren(icon('alert', 'sm'), errorMessage(error));
          problem.hidden = false;
          save.disabled = false;
        }
      });
    }

    function bookingForm(existing) {
      const start = existing ? new Date(existing.startsAt) : null;
      const hhmm = (d) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
      const name = h('input', { class: 'input', id: 'booking-name', maxlength: 60, autocomplete: 'off', value: existing?.guestName ?? '' });
      const phone = h('input', { class: 'input', id: 'booking-phone', type: 'tel', maxlength: 20, autocomplete: 'off', value: existing?.phone ?? '' });
      const size = h('input', { class: 'input', id: 'booking-size', type: 'number', min: 1, max: 30, inputmode: 'numeric', value: existing?.partySize ?? 2 });
      const date = h('input', { class: 'input', id: 'booking-date', type: 'date', value: start ? isoDay(start) : day });
      const at = h('input', { class: 'input', id: 'booking-time', type: 'time', step: 900, value: start ? hhmm(start) : '20:00' });
      const duration = h('select', { class: 'select', id: 'booking-duration' }, DURATIONS.map((m) => h('option', { value: m, selected: (existing?.durationMinutes ?? 90) === m }, tn('resv.minutes', m))));
      const table = h('select', { class: 'select', id: 'booking-table' });
      const note = h('input', { class: 'input', id: 'booking-note', maxlength: 140, autocomplete: 'off', value: existing?.note ?? '', placeholder: t('resv.notePlaceholder') });
      const problem = h('div', { class: 'form-error', hidden: true, role: 'alert' });
      const save = h('button', { class: 'btn primary', 'data-testid': 'booking-save' }, icon('check', 'sm'), t('common.save'));
      const startsAt = () => new Date(`${date.value}T${at.value || '00:00'}`);

      // the tables free for that time and party; the one already chosen stays chosen
      const loadTables = debounce(async () => {
        const chosen = table.value || (existing?.tableId ? String(existing.tableId) : '');
        let options = [];
        if (date.value && at.value && Number(size.value) > 0) {
          try {
            options = await api.freeTables({ startsAt: startsAt().toISOString(), partySize: Number(size.value), durationMinutes: Number(duration.value), except: existing?.id });
          } catch { options = []; }
        }
        mount(table, h('option', { value: '' }, t('resv.tableLater')),
          options.map((o) => h('option', { value: o.id, selected: String(o.id) === chosen }, `${o.code} · ${tn('floor.seats', o.seats)}`)));
      }, 200);
      [size, date, at, duration].forEach((el) => el.addEventListener('change', loadTables));
      loadTables();

      const modal = openModal({
        title: existing ? t('resv.editBooking', { name: existing.guestName }) : t('resv.addBooking'),
        body: [
          h('div', { class: 'grid-2' }, field('booking-name', t('resv.name'), name), field('booking-phone', t('resv.phoneOptional'), phone)),
          h('div', { class: 'grid-2' }, field('booking-date', t('resv.date'), date), field('booking-time', t('resv.time'), at)),
          h('div', { class: 'grid-2' }, field('booking-size', t('resv.partySize'), size), field('booking-duration', t('resv.duration'), duration)),
          field('booking-table', t('resv.tableLabel'), table),
          field('booking-note', t('resv.note'), note),
          problem,
        ],
        footer: (handle) => [h('button', { class: 'btn ghost', onclick: handle.close }, t('common.cancel')), save],
      });
      name.focus();
      save.addEventListener('click', async () => {
        save.disabled = true;
        const booking = {
          guestName: name.value, phone: phone.value || null, partySize: Number(size.value), startsAt: startsAt().toISOString(),
          durationMinutes: Number(duration.value), tableId: table.value ? Number(table.value) : null, note: note.value || null,
        };
        try {
          await (existing ? api.updateBooking(existing.id, booking) : api.book(booking));
          toast({ title: t('resv.savedToast', { name: booking.guestName, time: time(booking.startsAt) }) });
          modal.close();
          if (isoDay(startsAt()) !== day) { day = isoDay(startsAt()); parties = null; render(); }
          reload();
        } catch (error) {
          problem.replaceChildren(icon('alert', 'sm'), errorMessage(error));
          problem.hidden = false;
          save.disabled = false;
        }
      });
    }

    async function load() {
      const asked = day;
      try {
        const result = await api.reservations(asked);
        if (asked !== day) return;
        parties = result;
        render();
      } catch (error) {
        toast({ title: errorMessage(error), kind: 'error' });
      }
    }

    const reload = debounce(load, 200);
    const offReservation = bus.on('reservation', reload);
    // waits and lateness move on with the clock
    const timer = setInterval(() => { if (parties) render(); }, 30_000);

    render();
    load();
    return {
      destroy() {
        offReservation();
        reload.cancel();
        clearInterval(timer);
      },
    };
  },
};
