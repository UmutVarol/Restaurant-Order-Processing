// Point of sale: browse the menu, build a ticket, price it live on the server and send it to the kitchen.
import { debounce, h, mount } from '../core/dom.js';
import { icon } from '../core/icons.js';
import { t, tn } from '../core/i18n.js';
import { itemCount, money } from '../core/format.js';
import { api, ApiError, errorMessage } from '../core/api.js';
import { bus } from '../core/store.js';
import { toast } from '../core/ui.js';
import { enqueue, isOnline, lastReference, rememberReference } from '../core/offline.js';
import { chooseOptions, chosen, defaultOptionIds, optionKey, optionsPrice } from './options.js';
import { CATEGORIES, openOrderDrawer, PAY_ICONS, totals, TYPE_ICONS } from './shared.js';

const TAGS = ['VEGETARIAN', 'VEGAN', 'GLUTEN_FREE', 'SPICY'];
const TAG_ICONS = { VEGETARIAN: 'leaf', VEGAN: 'leaf', GLUTEN_FREE: 'wheat', SPICY: 'flame' };
const SHORTCUT = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent) ? '⌘↵' : 'Ctrl ↵';

// The ticket survives navigating away and back, like a real till.
const ticket = {
  type: 'DINE_IN',
  tableId: null,
  customerName: '',
  customerPhone: '',
  deliveryAddress: '',
  note: '',
  paymentMethod: 'CARD',
  // offline only: the guest already paid at the till, so the payment is sent with the order
  paidNow: false,
  couponCode: '',
  // later courses wait in the kitchen until the waiter fires them
  holdCourses: false,
  lines: [],
};
const COURSE_BY_CATEGORY = { STARTER: 1, DRINK: 1, MAIN: 2, DESSERT: 3 };
const filters = { category: 'ALL', query: '', tags: new Set() };
// Mirrors the server's per-line limit (OrderLineRequest.quantity), so the till never builds a ticket it cannot send.
const MAX_QUANTITY = 50;

function resetTicket() {
  Object.assign(ticket, {
    tableId: null, customerName: '', customerPhone: '', deliveryAddress: '', note: '', couponCode: '', holdCourses: false, paidNow: false, lines: [],
  });
}

export default {
  title: 'pos.title',
  subtitle: 'pos.sub',
  mount(container, { user, params }) {
    let menu = null;
    let tables = [];
    let coupons = [];
    let quote = null;
    let quoting = false;
    let placing = false;
    let panelVisible = false;
    let fieldErrors = {};
    let openNote = null;
    let couponDraft = ticket.couponCode;
    const cards = new Map();

    if (params.table) {
      ticket.type = 'DINE_IN';
      ticket.tableId = Number(params.table);
    }

    // ------------------------------------------------------------------ layout
    const search = h('input', {
      class: 'input', type: 'search', placeholder: t('pos.search'), value: filters.query, 'aria-label': t('pos.search'),
      oninput: (event) => { filters.query = event.target.value; renderGrid(); },
    });
    const categoryChips = h('div', { class: 'chips', role: 'tablist' });
    const tagChips = h('div', { class: 'chips' });
    const grid = h('div', { class: 'menu-grid' });

    const typeSection = h('div', { style: { display: 'flex', flexDirection: 'column', gap: '14px' } });
    const linesSection = h('div');
    const couponSection = h('div', { style: { display: 'flex', flexDirection: 'column', gap: '8px' } });
    const noteSection = h('div');
    const deliveryHint = h('div');
    const totalsSection = h('div');
    const paySection = h('div', { class: 'pay-methods' });
    const placeButton = h('button', { class: 'btn primary lg block', onclick: placeOrder });
    const orderPanel = h('aside', { class: 'card order-panel' });
    // On narrow screens the ticket sits below the menu; this bar keeps the running total in reach.
    const mobileBar = h('button', {
      class: 'mobile-cart', hidden: true, 'aria-label': t('pos.jumpToTicket'),
      onclick: () => orderPanel.scrollIntoView({ behavior: 'smooth', block: 'start' }),
    });
    const countLabel = h('span', { class: 'card-sub' });
    // Clearing is instant but reversible: the toast offers an undo instead of a confirmation dialog.
    const clearButton = h('button', {
      class: 'btn ghost sm',
      onclick: () => {
        const snapshot = structuredClone({ ...ticket });
        const draft = couponDraft;
        resetTicket();
        couponDraft = '';
        fieldErrors = {};
        refreshAll();
        toast({
          title: t('pos.cleared'), kind: 'info', timeout: 6000,
          action: { label: t('pos.undo'), run: () => { Object.assign(ticket, snapshot); couponDraft = draft; refreshAll(); } },
        });
      },
    }, icon('trash', 'sm'), t('pos.clear'));

    mount(container, h('div', { class: 'pos' },
      h('div', { class: 'pos-menu' },
        h('div', { class: 'pos-filters' },
          h('div', { class: 'input-icon' }, icon('search'), search, h('kbd', null, '/')),
          categoryChips,
          tagChips),
        grid),
      mount(orderPanel,
        h('div', { class: 'card-head' },
          h('div', { style: { flex: 1 } }, h('div', { class: 'card-title' }, t('pos.current')), countLabel),
          clearButton),
        h('div', { class: 'scroll' }, typeSection, linesSection, couponSection, noteSection),
        h('div', { class: 'checkout' }, deliveryHint, totalsSection, paySection, placeButton))),
      mobileBar);

    // ------------------------------------------------------------------ menu
    function visibleItems() {
      const needle = filters.query.trim().toLocaleLowerCase();
      return (menu || []).filter((item) => (filters.category === 'ALL' || item.category === filters.category)
        && [...filters.tags].every((tag) => item.tags.includes(tag))
        && (!needle || item.name.toLocaleLowerCase().includes(needle) || (item.description || '').toLocaleLowerCase().includes(needle)));
    }

    function renderFilters() {
      const counts = Object.fromEntries(CATEGORIES.map((c) => [c, (menu || []).filter((i) => i.category === c).length]));
      mount(categoryChips, ['ALL', ...CATEGORIES].map((category) => h('button', {
        class: ['chip', filters.category === category ? 'active' : ''],
        role: 'tab',
        'aria-selected': String(filters.category === category),
        onclick: () => { filters.category = category; renderFilters(); renderGrid(); },
      }, t(`cat.${category}`), h('span', { class: 'count' }, category === 'ALL' ? (menu || []).length : counts[category]))));
      mount(tagChips, TAGS.map((tag) => h('button', {
        class: ['chip', filters.tags.has(tag) ? 'active' : ''],
        style: { height: '36px', fontSize: '12px' },
        onclick: () => {
          if (filters.tags.has(tag)) filters.tags.delete(tag); else filters.tags.add(tag);
          renderFilters();
          renderGrid();
        },
      }, icon(TAG_ICONS[tag], 'sm'), t(`tag.${tag}`))));
    }

    function renderGrid() {
      cards.clear();
      if (!menu) {
        mount(grid, Array.from({ length: 8 }, () => h('div', { class: 'skeleton', style: { height: '250px', borderRadius: '20px' } })));
        return;
      }
      const items = visibleItems();
      if (!items.length) {
        mount(grid, h('div', { class: 'empty', style: { gridColumn: '1 / -1' } }, h('div', { class: 'art' }, '🔍'),
          h('h4', null, t('pos.noMatch')), h('p', null, t('pos.noMatchHint'))));
        return;
      }
      mount(grid, items.map(menuCard));
      updateBubbles();
    }

    function menuCard(item) {
      const card = h('button', {
        class: ['menu-card', item.available ? '' : 'soldout'],
        dataset: { cat: item.category },
        disabled: !item.available,
        'aria-label': `${item.name}, ${money(item.price)}`,
        onclick: () => addItem(item),
      },
      h('div', { class: 'art' }, h('span', null, item.icon || '🍽️'),
        item.popular ? h('span', { class: 'ribbon' }, icon('sparkle', 'sm'), t('pos.popular')) : null,
        item.available ? null : h('span', { class: 'soldout-label' }, t('pos.soldOut'))),
      h('div', { class: 'body' },
        h('div', { class: 'name' }, item.name),
        h('div', { class: 'desc' }, item.description),
        h('div', { class: 'tags' }, item.tags.map((tag) => h('span', { class: `tag t-${tag}` }, t(`tag.${tag}`)))),
        h('div', { class: 'foot' },
          h('div', null, h('div', { class: 'price' }, money(item.price)),
            h('div', { class: 'prep' }, icon('timer', 'sm'), t('common.minutes', { n: item.prepTimeMinutes }))),
          h('span', { class: 'add' }, icon('plus')))));
      cards.set(item.id, card);
      return card;
    }

    function updateBubbles() {
      for (const [id, card] of cards) {
        const quantity = ticket.lines.filter((line) => line.menuItemId === id).reduce((sum, line) => sum + line.quantity, 0);
        const existing = card.querySelector('.qty-bubble');
        if (!quantity) {
          existing?.remove();
        } else if (existing) {
          if (existing.textContent !== String(quantity)) {
            existing.textContent = quantity;
            existing.style.animation = 'none';
            void existing.offsetWidth;
            existing.style.animation = '';
          }
        } else {
          card.querySelector('.art').append(h('span', { class: 'qty-bubble' }, quantity));
        }
      }
    }

    // ------------------------------------------------------------------ ticket
    function menuItem(id) {
      return menu?.find((item) => item.id === id);
    }

    // A line is "this dish, with these options, with this note"; adding the same again only raises its quantity.
    const sameLine = (a, b) => a !== b && a.menuItemId === b.menuItemId && a.note.trim() === b.note.trim()
      && optionKey(a.optionIds) === optionKey(b.optionIds) && courseOf(a) === courseOf(b);
    const courseOf = (line) => line.course ?? COURSE_BY_CATEGORY[menuItem(line.menuItemId)?.category] ?? 1;

    function addItem(item) {
      const added = { menuItemId: item.id, quantity: 1, note: '', optionIds: defaultOptionIds(item) };
      const line = ticket.lines.find((l) => sameLine(l, added));
      if (line && line.quantity >= MAX_QUANTITY) return;
      if (line) line.quantity += 1;
      else ticket.lines.push(added);
      cartChanged();
    }

    async function editOptions(line) {
      const item = menuItem(line.menuItemId);
      const ids = await chooseOptions(item, line.optionIds);
      if (!ids || !ticket.lines.includes(line)) return;
      line.optionIds = ids;
      const twin = ticket.lines.find((l) => sameLine(l, line));
      if (twin) {
        twin.quantity = Math.min(MAX_QUANTITY, twin.quantity + line.quantity);
        ticket.lines.splice(ticket.lines.indexOf(line), 1);
      }
      cartChanged();
    }

    function changeQuantity(line, delta) {
      line.quantity = Math.min(MAX_QUANTITY, line.quantity + delta);
      if (line.quantity <= 0) ticket.lines.splice(ticket.lines.indexOf(line), 1);
      cartChanged();
    }

    function cartChanged() {
      renderLines();
      updateBubbles();
      renderPlaceButton();
      requestQuote();
    }

    function renderTypeSection() {
      const field = (name, label, input) => {
        const id = `ticket-${name}`;
        const control = input.matches('input, textarea, select') ? input : null;
        if (control) {
          control.id = id;
          if (fieldErrors[name]) control.setAttribute('aria-invalid', 'true');
        }
        return h('div', { class: ['field', fieldErrors[name] ? 'invalid' : ''] },
          control ? h('label', { for: id }, label) : h('span', { class: 'label' }, label), input,
          fieldErrors[name] ? h('div', { class: 'error', role: 'alert' }, fieldErrors[name]) : null);
      };
      const text = (name, placeholder, extra = {}) => h('input', {
        class: 'input', value: ticket[name], placeholder,
        oninput: (event) => { ticket[name] = event.target.value; },
        ...extra,
      });

      mount(typeSection,
        h('div', { class: 'segmented', role: 'radiogroup' }, ['DINE_IN', 'TAKEAWAY', 'DELIVERY'].map((type) => h('button', {
          class: ticket.type === type ? 'active' : '',
          role: 'radio',
          'aria-checked': String(ticket.type === type),
          onclick: () => {
            ticket.type = type;
            if (type === 'DELIVERY' && ticket.paymentMethod === 'CASH') ticket.paymentMethod = 'ONLINE';
            if (type !== 'DELIVERY' && ticket.paymentMethod === 'ONLINE') ticket.paymentMethod = 'CARD';
            fieldErrors = {};
            renderTypeSection();
            renderPay();
            requestQuote();
          },
        }, icon(TYPE_ICONS[type], 'sm'), t(`type.${type}`)))),
        ticket.type === 'DINE_IN' ? field('tableId', t('pos.pickTable'), tablePicker()) : null,
        field('customerName', t('pos.customer'), text('customerName', t('pos.customerPlaceholder'), { autocomplete: 'off', maxlength: 80 })),
        ticket.type === 'DELIVERY' ? field('customerPhone', t('pos.phone'), text('customerPhone', '+90 5__ ___ __ __', { type: 'tel', autocomplete: 'tel', inputmode: 'tel' })) : null,
        ticket.type === 'DELIVERY' ? field('deliveryAddress', t('pos.address'), text('deliveryAddress', t('pos.addressPlaceholder'), { autocomplete: 'street-address', maxlength: 255 })) : null);
    }

    // The order note was always sent to the API but had no input; the kitchen shows it on the ticket.
    function renderNote() {
      mount(noteSection, h('div', { class: 'field' }, h('label', { for: 'ticket-note' }, t('pos.note')), h('textarea', {
        class: 'textarea', id: 'ticket-note', rows: 2, maxlength: 255, placeholder: t('pos.notePlaceholder'), value: ticket.note,
        oninput: (event) => { ticket.note = event.target.value; },
      })),
      h('label', { class: 'checkbox hold-courses', title: t('courses.holdHint') },
        h('input', { type: 'checkbox', checked: ticket.holdCourses, 'data-testid': 'hold-courses',
          onchange: (event) => { ticket.holdCourses = event.target.checked; renderLines(); } }),
        t('courses.hold')));
    }

    function tablePicker() {
      return h('div', { class: 'table-picker' }, tables.map((table) => h('button', {
        class: [ticket.tableId === table.id ? 'active' : '', table.occupied ? 'busy' : ''],
        title: `${tn('floor.seats', table.seats)}${table.occupied ? ` · ${t('pos.occupied')}` : ''}`,
        onclick: () => {
          ticket.tableId = table.id;
          delete fieldErrors.tableId;
          renderTypeSection();
        },
      }, table.code)));
    }

    function renderLines() {
      const count = ticket.lines.reduce((sum, line) => sum + line.quantity, 0);
      countLabel.textContent = itemCount(count);
      clearButton.hidden = count === 0;
      if (!ticket.lines.length) {
        mount(linesSection, h('div', { class: 'empty', style: { padding: '28px 12px' } },
          h('div', { class: 'art' }, '🧾'), h('h4', null, t('pos.emptyTitle')), h('p', null, t('pos.emptyText'))));
        return;
      }
      mount(linesSection, h('div', { class: 'cart-lines' }, ticket.lines.map((line) => {
        const item = menuItem(line.menuItemId);
        const name = item?.name ?? `#${line.menuItemId}`;
        const priced = quote?.lines.find((q) => q.menuItemId === line.menuItemId && (q.note || '') === (line.note || '').trim()
          && optionKey(q.options.map((o) => o.choiceId)) === optionKey(line.optionIds));
        const unitPrice = item ? Number(item.price) + optionsPrice(item, line.optionIds) : 0;
        const lineTotal = priced?.lineTotal ?? unitPrice * line.quantity;
        const editing = openNote === line;
        const picked = chosen(item, line.optionIds);
        return h('div', { class: 'cart-line' },
          h('div', { class: 'emoji' }, item?.icon || '🍽️'),
          h('div', { class: 'info' },
            h('div', { class: 'name' }, item?.name ?? `#${line.menuItemId}`),
            item?.optionGroups?.length ? h('button', {
              class: 'line-options', 'data-testid': 'line-options', 'aria-label': t('options.edit', { name }),
              onclick: () => editOptions(line),
            }, icon('edit', 'sm'), picked.length ? picked.map((o) => o.choice).join(' · ') : t('options.choose')) : null,
            h('div', { class: 'meta' }, money(unitPrice),
              ticket.holdCourses ? h('button', {
                class: 'course-chip', 'data-testid': 'line-course', title: t('courses.change'),
                onclick: () => {
                  line.course = (courseOf(line) % 3) + 1;
                  // Moving a dish into a course that already has the same dish folds the two lines together.
                  const twin = ticket.lines.find((other) => other !== line && sameLine(other, line));
                  if (twin) {
                    twin.quantity = Math.min(MAX_QUANTITY, twin.quantity + line.quantity);
                    ticket.lines.splice(ticket.lines.indexOf(line), 1);
                  }
                  renderLines();
                },
              }, t('courses.short', { n: courseOf(line) })) : null,
              h('button', {
                class: ['note-btn', line.note ? 'has-note' : ''],
                onclick: () => { openNote = editing ? null : line; renderLines(); },
              }, line.note ? `“${line.note}”` : [icon('edit', 'sm'), t('pos.addNote')])),
            editing ? h('input', {
              class: 'input note-input', value: line.note, placeholder: t('pos.lineNotePlaceholder'), maxlength: 140, autofocus: true,
              oninput: (event) => { line.note = event.target.value; },
              onkeydown: (event) => { if (event.key === 'Enter') { openNote = null; renderLines(); requestQuote(); } },
              onblur: () => { if (openNote === line) { openNote = null; renderLines(); requestQuote(); } },
            }) : null),
          h('div', { class: 'right' },
            h('div', { class: 'total' }, money(lineTotal)),
            h('div', { class: 'stepper' },
              h('button', { 'aria-label': t('pos.decrease', { name }), onclick: () => changeQuantity(line, -1) }, icon(line.quantity === 1 ? 'trash' : 'minus', 'sm')),
              h('span', null, line.quantity),
              h('button', {
                'aria-label': t('pos.increase', { name }), disabled: line.quantity >= MAX_QUANTITY, onclick: () => changeQuantity(line, 1),
              }, icon('plus', 'sm')))));
      })));
      const noteInput = linesSection.querySelector('.note-input');
      if (noteInput) {
        noteInput.focus();
        noteInput.setSelectionRange(noteInput.value.length, noteInput.value.length);
      }
    }

    function renderCoupon() {
      const result = quote?.coupon;
      if (ticket.couponCode && result?.applied) {
        mount(couponSection, h('div', { class: 'coupon-applied' }, icon('tag'),
          h('div', null, h('b', null, result.code), h('div', { class: 'desc' }, result.description)),
          h('button', {
            class: 'icon-btn sm', 'aria-label': t('pos.removeCoupon'),
            onclick: () => { ticket.couponCode = ''; couponDraft = ''; renderCoupon(); requestQuote(); },
          }, icon('x', 'sm'))));
        return;
      }
      // The draft lives outside the input so a re-render (e.g. when a quote arrives) never wipes what is typed.
      const hadFocus = couponSection.contains(document.activeElement) && document.activeElement.tagName === 'INPUT';
      const input = h('input', {
        class: 'input', placeholder: t('pos.coupon'), value: couponDraft, maxlength: 32, 'aria-label': t('pos.coupon'),
        oninput: (event) => { couponDraft = event.target.value; },
        onkeydown: (event) => { if (event.key === 'Enter') apply(); },
      });
      const apply = () => {
        ticket.couponCode = couponDraft.trim().toUpperCase();
        requestQuote();
      };
      const error = ticket.couponCode && result && !result.applied ? couponError(result) : null;
      mount(couponSection,
        h('div', { class: 'coupon-row' }, h('div', { class: 'input-icon', style: { flex: 1 } }, icon('tag'), input),
          h('button', { class: 'btn', onclick: apply, disabled: !ticket.lines.length }, t('pos.apply'))),
        error ? h('div', { class: 'field invalid' }, h('div', { class: 'error' }, error)) : null,
        coupons.length ? h('div', { class: 'coupon-hint' }, h('span', { class: 'faint', style: { fontSize: '11.5px' } }, t('pos.couponHint')),
          coupons.map((coupon) => h('button', {
            title: coupon.description,
            onclick: () => { couponDraft = coupon.code; ticket.couponCode = coupon.code; renderCoupon(); requestQuote(); },
          }, coupon.code))) : null);
      if (hadFocus) {
        input.focus();
        input.setSelectionRange(input.value.length, input.value.length);
      }
    }

    function couponError(result) {
      const coupon = coupons.find((c) => c.code === result.code);
      return t(`error.${result.errorCode}`, {
        code: result.code,
        minimumSubtotal: coupon ? money(coupon.minimumSubtotal) : '',
      });
    }

    function renderTotals() {
      if (!ticket.lines.length) {
        mount(totalsSection, totals({ subtotal: 0, discount: 0, tax: 0, deliveryFee: 0, total: 0 }));
        mount(deliveryHint);
        return;
      }
      if (!quote) {
        mount(totalsSection, h('div', { style: { display: 'grid', gap: '8px' } },
          [70, 60, 40].map((w) => h('div', { class: 'skeleton', style: { height: '14px', width: `${w}%` } }))));
        return;
      }
      mount(totalsSection, totals(quote.pricing, { loading: quoting }),
        quote.estimated ? h('div', { class: 'estimate-note', 'data-testid': 'estimate' }, icon('info', 'sm'), t('offline.estimate')) : null);
      if (ticket.type === 'DELIVERY') {
        const remaining = Number(quote.amountUntilFreeDelivery);
        const threshold = Number(quote.freeDeliveryThreshold);
        mount(deliveryHint, remaining > 0
          ? h('div', { class: 'free-delivery' }, h('div', { class: 'row' }, icon('delivery', 'sm'), t('pos.freeDelivery', { amount: money(remaining) })),
            h('div', { class: 'progress' }, h('i', { style: { width: `${Math.min(100, ((threshold - remaining) / threshold) * 100)}%` } })))
          : h('div', { class: 'free-delivery done' }, h('div', { class: 'row' }, icon('check', 'sm'), t('pos.freeDeliveryDone'))));
      } else {
        mount(deliveryHint);
      }
    }

    function renderPay() {
      const offline = !isOnline();
      // an online payment needs the payment provider, so it cannot be offered offline
      if (offline && ticket.paymentMethod === 'ONLINE') ticket.paymentMethod = 'CARD';
      const methods = ticket.type === 'DELIVERY' ? ['ONLINE', 'CARD', 'CASH'] : ['CARD', 'CASH'];
      mount(paySection, h('div', { class: 'segmented' }, methods.map((method) => h('button', {
        class: ticket.paymentMethod === method ? 'active' : '',
        disabled: offline && method === 'ONLINE',
        title: offline && method === 'ONLINE' ? t('offline.noOnline') : null,
        onclick: () => { ticket.paymentMethod = method; renderPay(); },
      }, icon(PAY_ICONS[method], 'sm'), t(`pay.${method}`)))),
      offline ? h('label', { class: 'checkbox paid-now', title: t('offline.paidNowHint') },
        h('input', { type: 'checkbox', checked: ticket.paidNow, 'data-testid': 'paid-now', onchange: (event) => { ticket.paidNow = event.target.checked; } }),
        t('offline.paidNow', { method: t(`pay.${ticket.paymentMethod}`) })) : null);
    }

    function renderPlaceButton() {
      const total = quote && ticket.lines.length ? ` · ${money(quote.pricing.total)}` : '';
      placeButton.disabled = placing || !ticket.lines.length;
      const count = ticket.lines.reduce((sum, line) => sum + line.quantity, 0);
      mobileBar.hidden = count === 0 || panelVisible;
      mount(mobileBar, h('span', { class: 'badge' }, count), h('span', null, t('pos.current')),
        h('b', null, quote ? money(quote.pricing.total) : ''), icon('arrowDown', 'sm'));
      mount(placeButton, placing ? h('span', { class: 'spinner' })
        : [icon(isOnline() ? 'flash' : 'clock', 'sm'), `${t(isOnline() ? 'pos.place' : 'offline.place')}${total}`, h('span', { class: 'kbd' }, SHORTCUT)]);
    }

    function refreshAll() {
      renderTypeSection();
      renderNote();
      renderLines();
      renderCoupon();
      renderTotals();
      renderPay();
      renderPlaceButton();
      updateBubbles();
      requestQuote();
    }

    // ------------------------------------------------------------------ server round-trips
    const requestItems = () => ticket.lines.map((line) => ({
      menuItemId: line.menuItemId, quantity: line.quantity, note: line.note.trim() || null, optionIds: line.optionIds,
      course: ticket.holdCourses ? courseOf(line) : null,
    }));

    const unitPriceOf = (line) => {
      const item = menuItem(line.menuItemId);
      return item ? Number(item.price) + optionsPrice(item, line.optionIds) : 0;
    };

    /** Offline the server cannot price the cart: an estimate from the menu prices and the last tax rate seen. */
    function estimate() {
      const subtotal = ticket.lines.reduce((sum, line) => sum + unitPriceOf(line) * line.quantity, 0);
      const tax = Math.round(subtotal * (lastReference()?.taxRate ?? 0) * 100) / 100;
      return { estimated: true, lines: [], coupon: null, pricing: { subtotal, discount: 0, tax, deliveryFee: 0, total: subtotal + tax } };
    }

    // The cart the last server quote was for: it stays valid offline as long as the cart does not change.
    const cartKey = () => JSON.stringify([ticket.type, ticket.couponCode, requestItems()]);
    let quotedCart = null;
    const serverQuoteCurrent = () => quote && !quote.estimated && quotedCart === cartKey();

    let quoteSeq = 0;
    const requestQuote = debounce(async () => {
      if (!ticket.lines.length) {
        quote = null;
        renderTotals();
        renderCoupon();
        renderPlaceButton();
        return;
      }
      const seq = ++quoteSeq;
      if (!isOnline()) {
        if (!serverQuoteCurrent()) quote = estimate();
        renderTotals();
        renderPlaceButton();
        return;
      }
      quoting = true;
      renderTotals();
      try {
        const result = await api.quote({
          type: ticket.type,
          couponCode: ticket.couponCode || null,
          items: requestItems(),
        });
        if (seq !== quoteSeq) return;
        quote = result;
        quotedCart = cartKey();
        const taxable = Number(result.pricing.subtotal) - Number(result.pricing.discount);
        if (taxable > 0) rememberReference({ taxRate: Number(result.pricing.tax) / taxable });
      } catch (error) {
        if (seq !== quoteSeq) return;
        if (error instanceof ApiError && error.status === 0) { if (!serverQuoteCurrent()) quote = estimate(); }
        else toast({ title: errorMessage(error), kind: 'error' });
      } finally {
        if (seq === quoteSeq) {
          quoting = false;
          renderTotals();
          renderCoupon();
          if (!openNote) renderLines();
          renderPlaceButton();
        }
      }
    }, 180);

    async function placeOrder() {
      if (!ticket.lines.length || placing) return;
      placing = true;
      fieldErrors = {};
      renderPlaceButton();
      const body = {
          type: ticket.type,
          customerName: ticket.customerName.trim(),
          customerPhone: ticket.customerPhone.trim() || null,
          tableId: ticket.type === 'DINE_IN' ? ticket.tableId : null,
          deliveryAddress: ticket.type === 'DELIVERY' ? ticket.deliveryAddress.trim() || null : null,
          note: ticket.note.trim() || null,
          paymentMethod: ticket.paymentMethod,
          couponCode: ticket.couponCode || null,
          items: requestItems(),
          holdCourses: ticket.holdCourses,
      };
      try {
        if (!isOnline()) throw new ApiError(0, null);
        const order = await api.placeOrder(body);
        toast({
          title: t('pos.placed', { id: order.id }),
          text: t('pos.placedSub', { items: itemCount(order.itemCount), total: money(order.pricing.total) }),
          action: { label: t('common.view'), run: () => openOrderDrawer(order.id, user) },
        });
        resetTicket();
        couponDraft = '';
        quote = null;
        loadTables();
      } catch (error) {
        if (error instanceof ApiError && error.status === 0) {
          await queueOffline(body);
        } else {
          if (error instanceof ApiError && error.code === 'VALIDATION_FAILED') fieldErrors = error.fieldErrors();
          toast({ title: errorMessage(error), kind: 'error' });
        }
      } finally {
        placing = false;
        refreshAll();
      }
    }

    /** The server cannot be reached: keep the order (and the payment taken for it) on this till until it can. */
    async function queueOffline(body) {
      const real = serverQuoteCurrent();
      const seen = {
        unitPrices: real ? quote.lines.map((line) => Number(line.unitPrice)) : ticket.lines.map(unitPriceOf),
        total: real ? Number(quote.pricing.total) : null,
        estimate: quote ? Number(quote.pricing.total) : null,
        tableCode: tables.find((table) => table.id === body.tableId)?.code ?? null,
      };
      const payment = ticket.paidNow && ticket.paymentMethod !== 'ONLINE' ? { method: ticket.paymentMethod } : null;
      try {
        await enqueue(body, seen, payment);
      } catch {
        toast({ title: t('offline.queueFailed'), kind: 'error' });
        return;
      }
      toast({ title: t('offline.queued', { name: body.customerName }), text: t('offline.queuedText'), kind: 'info' });
      resetTicket();
      couponDraft = '';
      quote = null;
    }

    async function loadTables() {
      try {
        tables = await api.tables();
        if (ticket.type === 'DINE_IN') renderTypeSection();
      } catch { /* the picker simply stays as it was */ }
    }

    async function load() {
      renderFilters();
      renderGrid();
      refreshAll();
      const [menuResult, tableResult, couponResult] = await Promise.allSettled([api.menu(), api.tables(), api.coupons()]);
      const saved = lastReference();
      // offline, the till works from the menu and floor plan it saw last
      menu = menuResult.status === 'fulfilled' ? menuResult.value : saved?.menu ?? [];
      tables = tableResult.status === 'fulfilled' ? tableResult.value : saved?.tables ?? [];
      coupons = couponResult.status === 'fulfilled' ? couponResult.value : [];
      if (menuResult.status === 'fulfilled') rememberReference({ menu, tables: tableResult.status === 'fulfilled' ? tables : null });
      if (menuResult.status === 'rejected' && !(menuResult.reason?.status === 0 && saved?.menu?.length)) {
        toast({ title: errorMessage(menuResult.reason), kind: 'error' });
      }
      // drop lines whose dish disappeared from the menu since the ticket was started
      ticket.lines = ticket.lines.filter((line) => menuItem(line.menuItemId)?.available);
      // options may have changed in the meantime too; keep only choices the dish still offers
      for (const line of ticket.lines) {
        const offered = new Set(menuItem(line.menuItemId).optionGroups?.flatMap((g) => g.choices.map((c) => c.id)) ?? []);
        const kept = (line.optionIds ?? []).filter((id) => offered.has(id));
        line.optionIds = kept.length || !offered.size ? kept : defaultOptionIds(menuItem(line.menuItemId));
      }
      renderFilters();
      renderGrid();
      refreshAll();
    }

    const onKey = (event) => {
      if (event.key === '/' && document.activeElement?.tagName !== 'INPUT' && document.activeElement?.tagName !== 'TEXTAREA') {
        event.preventDefault();
        search.focus();
      }
      if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        placeOrder();
      }
    };
    document.addEventListener('keydown', onKey);
    // The floating total is a shortcut to the ticket; once the ticket itself is on screen it would only cover it.
    const panelObserver = 'IntersectionObserver' in window ? new IntersectionObserver(([entry]) => {
      panelVisible = entry.isIntersecting;
      renderPlaceButton();
    }, { threshold: 0.05 }) : null;
    panelObserver?.observe(orderPanel);
    const reloadTables = debounce(loadTables, 400);
    const offOrder = bus.on('order', reloadTables);
    // A dish that sold out (or came back) shows at once; the ticket is left alone, the server has the last word.
    const reloadMenu = debounce(async () => {
      try {
        menu = await api.menu();
        renderGrid();
      } catch { /* the grid stays as it was */ }
    }, 300);
    const offStock = bus.on('stock', reloadMenu);
    const offConnectivity = bus.on('connectivity', () => { renderPay(); renderPlaceButton(); requestQuote(); });

    load();
    return {
      destroy() {
        document.removeEventListener('keydown', onKey);
        panelObserver?.disconnect();
        offOrder();
        offStock();
        offConnectivity();
        reloadTables.cancel();
        reloadMenu.cancel();
        requestQuote.cancel();
      },
    };
  },
};
