// F2: the day's card. When a manager closes today at the till, the day appears once as a card on receipt paper:
// orders, revenue and the average bill count up, the hours rise, the star dish of the day settles in. "Share" hands
// the card as a picture to the phone's share sheet (WhatsApp and the like); elsewhere it is saved as a PNG. The
// picture is drawn on this device and never sent to the server.
import { h } from '../core/dom.js';
import { lang, t } from '../core/i18n.js';
import { dayLabel, money, number } from '../core/format.js';
import { api, errorMessage } from '../core/api.js';
import { fitAccent } from '../core/brand.js';
import { countUp, openModal, toast } from '../core/ui.js';

const SOFRA = '#e4572e';
const PAPER = '#fffdf8';
const INK = '#2b2522';
const MUTED = '#6f645b';
const BAR = '#e3b98a';
const SOFT = '#f6efe3';

/** Opening hours shown on the card: 10:00 to midnight, widened when orders came earlier. */
function hoursOf(hourly) {
  const active = hourly.filter((point) => point.orders > 0).map((point) => point.hour);
  const from = Math.min(10, ...active);
  return hourly.filter((point) => point.hour >= from);
}

/** Opens the card for today; {@code date} is the day that was closed (yyyy-mm-dd). */
export async function openDayCard(date) {
  let data;
  let restaurant = 'Sofra';
  let accent = SOFRA;
  try {
    const [dashboard, config, brand] = await Promise.all([
      api.dashboard(1), api.publicConfig().catch(() => null), api.branding().catch(() => null)]);
    data = dashboard;
    if (config?.restaurantName) restaurant = config.restaurantName;
    if (brand?.accent) accent = fitAccent(brand.accent).light.brand;
  } catch (error) {
    toast({ title: errorMessage(error), kind: 'error' });
    return;
  }
  const day = {
    date,
    restaurant,
    accent,
    orders: Number(data.current.orders),
    revenue: Number(data.current.revenue),
    average: Number(data.current.averageOrderValue),
    star: data.topItems[0] || null,
    hours: hoursOf(data.hourly),
  };
  const peak = day.hours.reduce((best, point) => (point.orders > (best?.orders ?? 0) ? point : best), null);
  const card = cardElement(day, peak);
  const canShare = typeof navigator.canShare === 'function'
    && navigator.canShare({ files: [new File([new Blob()], 'day.png', { type: 'image/png' })] });

  const modal = openModal({
    title: t('dayCard.open'),
    size: 'day-card-modal',
    body: h('div', { class: 'day-card-wrap' }, card, h('small', { class: 'faint' }, t('dayCard.private'))),
    footer: (handle) => [
      h('button', { class: 'btn', onclick: handle.close }, t('common.close')),
      h('button', {
        class: 'btn primary', 'data-testid': 'day-card-share',
        onclick: async () => {
          const blob = await drawCard(day, peak);
          const name = `sofra-${date}.png`;
          if (canShare) {
            try {
              await navigator.share({ files: [new File([blob], name, { type: 'image/png' })], title: `${restaurant} · ${t('dayCard.title')}` });
              return;
            } catch (error) {
              if (error?.name === 'AbortError') return;
            }
          }
          const link = h('a', { href: URL.createObjectURL(blob), download: name });
          document.body.append(link);
          link.click();
          link.remove();
          setTimeout(() => URL.revokeObjectURL(link.href), 10_000);
        },
      }, t(canShare ? 'dayCard.share' : 'dayCard.download')),
    ],
  });
  // the figures count up once the card is on screen; reduced motion shows them at once
  countUp(card.querySelector('[data-fig="orders"]'), day.orders, (v) => number(Math.round(v)));
  countUp(card.querySelector('[data-fig="revenue"]'), day.revenue, (v) => money(v));
  countUp(card.querySelector('[data-fig="average"]'), day.average, (v) => money(v));
  return modal;
}

function cardElement(day, peak) {
  const max = Math.max(1, ...day.hours.map((point) => point.orders));
  const hh = (hour) => `${String(hour).padStart(2, '0')}:00`;
  const first = day.hours[0]?.hour ?? 10;
  const last = day.hours[day.hours.length - 1]?.hour ?? 23;
  return h('article', { class: 'day-card', style: { '--day-accent': day.accent }, 'data-testid': 'day-card' },
    h('header', null,
      h('small', { class: 'day-card-rest' }, day.restaurant),
      h('h3', null, t('dayCard.title')),
      h('span', { class: 'day-card-date' }, dayLabel(day.date, { weekday: 'long', day: 'numeric', month: 'long' }))),
    day.orders === 0 ? h('p', { class: 'day-card-empty' }, t('dayCard.empty')) : [
      h('div', { class: 'day-card-figs' },
        figure(t('dayCard.orders'), 'orders', number(day.orders)),
        figure(t('dayCard.revenue'), 'revenue', money(day.revenue)),
        figure(t('dayCard.aov'), 'average', money(day.average))),
      day.star ? h('div', { class: 'day-card-star' },
        h('span', { class: 'emoji', 'aria-hidden': 'true' }, day.star.icon || '🍽️'),
        h('div', null, h('small', null, t('dayCard.star')),
          h('b', null, h('bdi', null, day.star.name), ` · ${t('dayCard.sold', { n: number(day.star.quantity) })}`))) : null,
      peak ? h('div', { class: 'day-card-hours' },
        h('small', null, t('dayCard.busiest'), ' ', h('b', { class: 'num', dir: 'ltr' }, `${hh(peak.hour)}–${hh((peak.hour + 1) % 24)}`)),
        h('div', { class: 'day-card-bars', 'aria-hidden': 'true' }, day.hours.map((point, i) => h('i', {
          class: point === peak ? 'peak' : '', style: { '--i': i, height: `${Math.max(4, (point.orders / max) * 100)}%` },
        }))),
        h('div', { class: 'day-card-axis', 'aria-hidden': 'true' }, h('span', null, hh(first)), h('span', null, hh(last)))) : null,
    ],
    h('footer', null, h('span', null, day.restaurant), h('span', null, 'Sofra')));
}

function figure(label, key, value) {
  return h('div', { class: 'day-card-fig' }, h('small', null, label), h('b', { class: 'num', 'data-fig': key }, value));
}

// ---------------------------------------------------------------------------- the picture

/** Draws the card at 1080 × 1350 (a phone's share size), right to left in Arabic, in the page's own fonts. */
async function drawCard(day, peak) {
  await document.fonts?.ready;
  const W = 1080;
  const H = 1350;
  const P = 88;
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext('2d');
  const rtl = lang() === 'ar';
  ctx.direction = rtl ? 'rtl' : 'ltr';
  // x measured from the reading start; text anchored at the reading start unless asked otherwise
  const X = (x) => (rtl ? W - x : x);
  const text = (value, x, y, font, colour, align = 'start') => {
    ctx.font = font;
    ctx.fillStyle = colour;
    ctx.textAlign = align;
    ctx.fillText(value, X(x), y);
  };
  const serif = (size, weight = 600) => `${weight} ${size}px Fraunces, Georgia, serif`;
  const sans = (size, weight = 500) => `${weight} ${size}px Inter, system-ui, sans-serif`;

  ctx.fillStyle = PAPER;
  ctx.fillRect(0, 0, W, H);
  ctx.fillStyle = day.accent;
  ctx.fillRect(0, 0, W, 14);

  text(day.restaurant, P, 130, sans(34, 600), MUTED);
  text(t('dayCard.title'), P, 222, serif(76), INK);
  text(dayLabel(day.date, { weekday: 'long', day: 'numeric', month: 'long' }), P, 280, sans(34), MUTED);

  if (day.orders === 0) {
    text(t('dayCard.empty'), P, 420, sans(40), MUTED);
  } else {
    const col = (W - 2 * P) / 3;
    [[t('dayCard.orders'), number(day.orders)], [t('dayCard.revenue'), money(day.revenue)], [t('dayCard.aov'), money(day.average)]]
      .forEach(([label, value], i) => {
        const x = P + i * col;
        ctx.strokeStyle = '#cfc3b2';
        ctx.setLineDash([8, 8]);
        ctx.beginPath();
        ctx.moveTo(X(x), 352);
        ctx.lineTo(X(x + col - 24), 352);
        ctx.stroke();
        ctx.setLineDash([]);
        text(label, x, 400, sans(28, 600), MUTED);
        // a large sum shrinks until it fits its column, in any language's number format
        let size = i === 0 ? 64 : 52;
        ctx.font = serif(size);
        while (size > 28 && ctx.measureText(value).width > col - 28) ctx.font = serif(size -= 2);
        text(value, x, 470, serif(size), INK);
      });

    if (day.star) {
      ctx.fillStyle = SOFT;
      roundRect(ctx, P, 540, W - 2 * P, 150, 24);
      text(day.star.icon || '🍽️', P + 36, 640, sans(68), INK);
      text(t('dayCard.star'), P + 140, 598, sans(28, 600), MUTED);
      text(`${day.star.name} · ${t('dayCard.sold', { n: number(day.star.quantity) })}`, P + 140, 650, sans(40, 650), INK);
    }

    if (peak) {
      const hh = (hour) => `${String(hour).padStart(2, '0')}:00`;
      // the hours keep their own left-to-right order inside an Arabic line (isolated, like <bdi>)
      text(`${t('dayCard.busiest')}  \u2066${hh(peak.hour)}–${hh((peak.hour + 1) % 24)}\u2069`, P, 790, sans(30, 600), MUTED);
      const top = 830;
      const bottom = 1150;
      const max = Math.max(1, ...day.hours.map((point) => point.orders));
      const gap = 10;
      const width = (W - 2 * P - gap * (day.hours.length - 1)) / day.hours.length;
      day.hours.forEach((point, i) => {
        const height = Math.max(8, ((bottom - top) * point.orders) / max);
        const x = P + i * (width + gap);
        ctx.fillStyle = point === peak ? day.accent : BAR;
        roundRect(ctx, rtl ? W - x - width : x, bottom - height, width, height, 8, true);
      });
      text(hh(day.hours[0].hour), P, 1196, sans(26), MUTED);
      text(hh(day.hours[day.hours.length - 1].hour), W - P, 1196, sans(26), MUTED, 'end');
    }
  }

  ctx.strokeStyle = '#e9e0d2';
  ctx.beginPath();
  ctx.moveTo(P, 1250);
  ctx.lineTo(W - P, 1250);
  ctx.stroke();
  text('Sofra', W - P, 1300, serif(30), MUTED, 'end');
  return new Promise((resolve) => canvas.toBlob(resolve, 'image/png'));
}

/** A filled rectangle with rounded corners; {@code topOnly} rounds only the two at the top (a bar). */
function roundRect(ctx, x, y, w, h, r, topOnly = false) {
  ctx.beginPath();
  ctx.moveTo(x + r, y);
  ctx.arcTo(x + w, y, x + w, y + h, r);
  if (topOnly) { ctx.lineTo(x + w, y + h); ctx.lineTo(x, y + h); } else { ctx.arcTo(x + w, y + h, x, y + h, r); ctx.arcTo(x, y + h, x, y, r); }
  ctx.arcTo(x, y, x + w, y, r);
  ctx.closePath();
  ctx.fill();
}
