// Locale-aware formatting for money, numbers, times and durations.
import { lang, locale, t, tn } from './i18n.js';

const cache = new Map();

function formatter(kind, options) {
  const key = `${locale()}|${kind}`;
  if (!cache.has(key)) cache.set(key, new Intl.NumberFormat(locale(), options));
  return cache.get(key);
}

export function money(amount) {
  return formatter('money', {
    style: 'currency', currency: 'TRY', currencyDisplay: 'narrowSymbol', minimumFractionDigits: 2, maximumFractionDigits: 2,
  }).format(Number(amount ?? 0));
}

/** Whole-lira amounts for compact displays such as charts and KPI tiles. */
export function moneyShort(amount) {
  const value = Number(amount ?? 0);
  if (Math.abs(value) >= 100_000) {
    return formatter('moneyCompact', {
      style: 'currency', currency: 'TRY', currencyDisplay: 'narrowSymbol', notation: 'compact', maximumFractionDigits: 1,
    }).format(value);
  }
  return formatter('moneyWhole', {
    style: 'currency', currency: 'TRY', currencyDisplay: 'narrowSymbol', maximumFractionDigits: 0,
  }).format(value);
}

export function number(value, digits = 0) {
  return new Intl.NumberFormat(locale(), { maximumFractionDigits: digits, minimumFractionDigits: digits }).format(value ?? 0);
}

export function percent(value) {
  return new Intl.NumberFormat(locale(), { style: 'percent', maximumFractionDigits: 0 }).format(value ?? 0);
}

let timeFormatter = null;
function timeFormat() {
  if (timeFormatter?.resolvedOptions().locale !== locale()) {
    timeFormatter = new Intl.DateTimeFormat(locale(), { hour: '2-digit', minute: '2-digit' });
  }
  return timeFormatter;
}

export function time(iso) {
  return timeFormat().format(new Date(iso));
}

export function dateTime(iso) {
  const date = new Date(iso);
  const today = new Date();
  const sameDay = date.toDateString() === today.toDateString();
  if (sameDay) return time(iso);
  return date.toLocaleString(locale(), { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
}

export function dayLabel(isoDate, options = { weekday: 'short' }) {
  return new Date(`${isoDate}T12:00:00`).toLocaleDateString(locale(), options);
}

/** Minutes elapsed since the given instant. */
export function minutesSince(iso, now = Date.now()) {
  return Math.max(0, (now - new Date(iso).getTime()) / 60000);
}

/** Compact elapsed time: "45s", "12:04", "1h 05m". */
export function elapsed(iso, now = Date.now()) {
  const seconds = Math.max(0, Math.floor((now - new Date(iso).getTime()) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}:${String(seconds % 60).padStart(2, '0')}`;
  const hours = Math.floor(minutes / 60);
  return `${hours}${lang() === 'tr' ? 'sa' : 'h'} ${String(minutes % 60).padStart(2, '0')}${lang() === 'tr' ? 'dk' : 'm'}`;
}

export function durationMinutes(minutes) {
  return t('common.minutes', { n: number(minutes, minutes < 10 ? 1 : 0) });
}

export function itemCount(n) {
  return tn('pos.items', n);
}
