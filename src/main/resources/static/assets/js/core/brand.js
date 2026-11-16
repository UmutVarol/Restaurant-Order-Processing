// D1: a restaurant's own colour and logo on its guests' screens (QR menu, payment page, receipt) instead of Sofra's.
// The colour is adjusted so text stays readable on it and in it (WCAG AA, 4.5:1) in the light and the dark theme.
import { h } from './dom.js';
import { logoMark } from './icons.js';
import { backendFetch } from './api.js';

const WHITE = [255, 255, 255];
const INK = [31, 26, 23];
/** The dark theme's card surface (css/app.css). */
const DARK_SURFACE = [26, 23, 21];
const SOFRA = '#e4572e';
export const PRESETS = ['#e4572e', '#2f6f4f', '#8a2c3b', '#1f5f8b', '#b26a00', '#5b3fa8'];

const hexToRgb = (hex) => { const n = parseInt(hex.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const rgbToHex = (c) => `#${c.map((x) => Math.max(0, Math.min(255, Math.round(x))).toString(16).padStart(2, '0')).join('')}`;
const mix = (a, b, share) => a.map((v, i) => v + (b[i] - v) * share);

function luminance(c) {
  const [r, g, b] = c.map((v) => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

export function contrast(a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

/** Darkens (or lightens, towards {@code to}) a colour in small steps until it reaches 4.5:1 against {@code against}. */
function until(colour, against, to) {
  let c = colour.slice();
  for (let i = 0; i < 60 && contrast(c, against) < 4.5; i++) c = mix(c, to, 0.06);
  return c;
}

/**
 * The colours a guest's screen uses for an accent. In the light theme white text sits on it and it sits as text on
 * white, which is one and the same ratio; in the dark theme it is lightened until it reads on the dark surface.
 */
export function fitAccent(hex) {
  const base = hexToRgb(hex);
  const light = until(base, WHITE, [0, 0, 0]);
  const dark = until(base, DARK_SURFACE, WHITE);
  const darkInk = contrast(dark, INK) >= contrast(dark, WHITE) ? INK : WHITE;
  return {
    from: contrast(base, WHITE),
    ratio: contrast(light, WHITE),
    adjusted: rgbToHex(light) !== hex.toLowerCase(),
    light: { brand: rgbToHex(light), strong: rgbToHex(mix(light, [0, 0, 0], 0.15)), soft: rgbToHex(mix(light, WHITE, 0.88)), ink: '#ffffff' },
    dark: { brand: rgbToHex(dark), strong: rgbToHex(mix(dark, WHITE, 0.2)), soft: `${rgbToHex(dark)}24`, ink: rgbToHex(darkInk) },
  };
}

// ------------------------------------------------------------------------------------------ the guest's screen

const current = { name: null, accent: null, logo: null };
const logos = new Map();
let observer = null;

function paintThemeColour() {
  const meta = document.querySelector('meta[name="theme-color"]');
  if (!meta) return;
  if (!current.accent) { meta.setAttribute('content', SOFRA); return; }
  const fit = fitAccent(current.accent);
  meta.setAttribute('content', document.documentElement.dataset.theme === 'dark' ? fit.dark.brand : fit.light.brand);
}

/** Paints the page in {@code accent}, or in Sofra's colours when it is null. */
export function paintAccent(accent, target = document.documentElement) {
  const valid = typeof accent === 'string' && /^#[0-9a-f]{6}$/i.test(accent);
  if (!valid) {
    target.classList.remove('branded');
    ['l', 'd'].forEach((theme) => ['', '-strong', '-soft', '-ink'].forEach((part) => target.style.removeProperty(`--b-${theme}${part}`)));
    return;
  }
  const fit = fitAccent(accent);
  for (const [theme, colours] of [['l', fit.light], ['d', fit.dark]]) {
    target.style.setProperty(`--b-${theme}`, colours.brand);
    target.style.setProperty(`--b-${theme}-strong`, colours.strong);
    target.style.setProperty(`--b-${theme}-soft`, colours.soft);
    target.style.setProperty(`--b-${theme}-ink`, colours.ink);
  }
  target.classList.add('branded');
}

/** Fetches a logo once per version, as a data: address (the page's CSP allows those for images). */
async function loadLogo(path, version) {
  const key = `${path}?v=${version}`;
  if (logos.has(key)) return logos.get(key);
  try {
    const response = await backendFetch(`api/v1${path}?v=${version}`, { headers: { Accept: 'image/png, image/webp' } });
    if (!response.ok) return null;
    const blob = await response.blob();
    if (!/^image\/(png|webp)$/.test(blob.type)) return null;
    const url = await new Promise((resolve) => {
      const reader = new FileReader();
      reader.onload = () => resolve(String(reader.result));
      reader.onerror = () => resolve(null);
      reader.readAsDataURL(blob);
    });
    logos.set(key, url);
    return url;
  } catch {
    return null;
  }
}

/** The restaurant's name, colour and logo on this guest's screen; {@code logoPath} is under /api/v1. */
export async function useGuestBrand(name, brand, logoPath) {
  current.name = name || null;
  current.accent = brand?.accent || null;
  paintAccent(current.accent);
  paintThemeColour();
  if (!observer) {
    observer = new MutationObserver(paintThemeColour);
    observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  }
  current.logo = brand?.logoVersion != null ? await loadLogo(logoPath, brand.logoVersion) : null;
  return current;
}

/** Back to Sofra's own look, when the guest leaves the restaurant's pages. */
export function leaveGuestBrand() {
  current.name = null;
  current.accent = null;
  current.logo = null;
  paintAccent(null);
  paintThemeColour();
  observer?.disconnect();
  observer = null;
}

function initials(name) {
  return name.split(/\s+/).filter(Boolean).slice(0, 2).map((word) => [...word][0]).join('').toLocaleUpperCase();
}

/** The mark at the top of a guest's screen: the logo, the restaurant's initials in its colour, or Sofra's mark. */
export function guestMark() {
  if (current.logo) return h('img', { class: 'guest-logo', src: current.logo, alt: '', 'data-testid': 'guest-logo' });
  if (current.name && current.accent) return h('span', { class: 'guest-initials', 'aria-hidden': 'true' }, initials(current.name));
  return logoMark();
}

export function guestName() {
  return current.name || 'Sofra';
}
