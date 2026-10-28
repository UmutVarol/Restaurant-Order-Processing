// Hand-drawn 24×24 stroke icons, rendered with currentColor.
import { s } from './dom.js';

const PATHS = {
  logo: null,
  pos: '<path d="M5 8h14l-1.2 11.2a2 2 0 0 1-2 1.8H8.2a2 2 0 0 1-2-1.8L5 8Z"/><path d="M9 8V7a3 3 0 0 1 6 0v1"/><path d="M12 12v5M9.5 14.5h5"/>',
  kitchen: '<path d="M6 13.5A4 4 0 0 1 7.5 6a4.5 4.5 0 0 1 9 0 4 4 0 0 1 1.5 7.5V17H6v-3.5Z"/><path d="M6 20h12M9 17v3M15 17v3"/>',
  orders: '<path d="M6 3h12v18l-2.5-1.6L13 21l-2.5-1.6L8 21l-2-1.3V3Z"/><path d="M9 8h6M9 12h6M9 16h3"/>',
  floor: '<circle cx="12" cy="12" r="4.5"/><path d="M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5"/>',
  dashboard: '<path d="M3 20h18"/><rect x="5" y="11" width="3" height="6" rx="1"/><rect x="10.5" y="6" width="3" height="11" rx="1"/><rect x="16" y="13" width="3" height="4" rx="1"/>',
  menu: '<path d="M4 5.5A1.5 1.5 0 0 1 5.5 4H10a2 2 0 0 1 2 2v14a2 2 0 0 0-2-2H4V5.5Z"/><path d="M20 5.5A1.5 1.5 0 0 0 18.5 4H14a2 2 0 0 0-2 2v14a2 2 0 0 1 2-2h6V5.5Z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z"/>',
  globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
  logout: '<path d="M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3"/><path d="M10 17l-5-5 5-5M5 12h11"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  minus: '<path d="M5 12h14"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  trash: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3"/>',
  edit: '<path d="M4 20h4L19 9a2.8 2.8 0 0 0-4-4L4 16v4Z"/><path d="m13.5 6.5 4 4"/>',
  check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
  phone: '<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2Z"/>',
  pin: '<path d="M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21Z"/><circle cx="12" cy="9.5" r="2.5"/>',
  note: '<path d="M5 4h14v11l-5 5H5V4Z"/><path d="M14 20v-5h5M9 9h6M9 13h3"/>',
  ticket: '<path d="M3 8a2 2 0 0 0 2-2h14a2 2 0 0 0 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 0-2 2H5a2 2 0 0 0-2-2v-2a2 2 0 0 0 0-4V8Z"/><path d="M9 6v12" stroke-dasharray="2 2"/>',
  card: '<rect x="3" y="5" width="18" height="14" rx="2.5"/><path d="M3 10h18M7 15h3"/>',
  cash: '<rect x="2.5" y="6" width="19" height="12" rx="2"/><circle cx="12" cy="12" r="2.5"/><path d="M6 9.5v5M18 9.5v5"/>',
  online: '<rect x="6" y="2.5" width="12" height="19" rx="2.5"/><path d="M10.5 18.5h3"/>',
  dinein: '<path d="M7 3v8M5 3v4a2 2 0 0 0 4 0V3M7 11v10"/><path d="M17 21V3c-2 1-3 3.5-3 6.5V13h3"/>',
  takeaway: '<path d="M6 8h12l-1 12H7L6 8Z"/><path d="M9 8a3 3 0 0 1 6 0"/>',
  delivery: '<circle cx="6.5" cy="17" r="2.5"/><circle cx="17.5" cy="17" r="2.5"/><path d="M9 17h6M4 13l2-5h5l3 5M14 8h3l3 6M11 8V5h3"/>',
  bell: '<path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4l2-2Z"/><path d="M10 21h4"/>',
  bellOff: '<path d="M8.5 5.2A6 6 0 0 1 18 11v4M6 11v5l-2 2h14M10 21h4M3 3l18 18"/>',
  printer: '<path d="M7 9V3h10v6"/><rect x="3" y="9" width="18" height="8" rx="2"/><path d="M7 14h10v7H7z"/>',
  chevronRight: '<path d="m9 6 6 6-6 6"/>',
  chevronLeft: '<path d="m15 6-6 6 6 6"/>',
  arrowUp: '<path d="M12 19V5M6 11l6-6 6 6"/>',
  arrowDown: '<path d="M12 5v14M6 13l6 6 6-6"/>',
  arrowRight: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  refresh: '<path d="M20 11a8 8 0 0 0-14.6-4.5L4 8M4 4v4h4M4 13a8 8 0 0 0 14.6 4.5L20 16M20 20v-4h-4"/>',
  alert: '<path d="M12 3 2 20h20L12 3Z"/><path d="M12 10v4M12 17.5v.5"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 7.5v.5"/>',
  flame: '<path d="M12 21a6 6 0 0 0 6-6c0-4-3-6-4-10-2 2-2.5 4-2.5 5.5C10 9.5 9 8 9 6c-2 2-3 5-3 9a6 6 0 0 0 6 6Z"/>',
  leaf: '<path d="M5 19c0-8 5-14 15-14 0 10-6 15-14 15"/><path d="M5 19c3-5 6-7 10-9"/>',
  sparkle: '<path d="M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z"/><path d="M19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8L19 16Z"/>',
  wheat: '<path d="M12 21V9M12 9c-3 0-4-2-4-5 3 0 4 2 4 5ZM12 9c3 0 4-2 4-5-3 0-4 2-4 5ZM12 14c-3 0-4-2-4-5M12 14c3 0 4-2 4-5"/><path d="M4 4l16 16" opacity=".9"/>',
  tag: '<path d="M3 12V4a1 1 0 0 1 1-1h8l9 9-9 9-9-9Z"/><circle cx="7.5" cy="7.5" r="1.5"/>',
  coins: '<ellipse cx="9" cy="7" rx="6" ry="3"/><path d="M3 7v5c0 1.7 2.7 3 6 3s6-1.3 6-3V7"/><path d="M9 15v2c0 1.7 2.7 3 6 3s6-1.3 6-3v-5c0-1.7-2.7-3-6-3"/>',
  receipt: '<path d="M6 3h12v18l-3-2-3 2-3-2-3 2V3Z"/><path d="M9 8h6M9 12h6"/>',
  users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><path d="M16 4.5a3.5 3.5 0 0 1 0 7M21.5 20a6.5 6.5 0 0 0-4-6"/>',
  timer: '<circle cx="12" cy="13" r="8"/><path d="M12 9v4l2.5 2.5M9 2h6"/>',
  trend: '<path d="M3 17l6-6 4 4 8-8"/><path d="M15 7h6v6"/>',
  basket: '<path d="M3 10h18l-2 10H5L3 10Z"/><path d="m8 10 4-6 4 6M9 14v3M15 14v3"/>',
  table: '<rect x="3" y="8" width="18" height="4" rx="1"/><path d="M6 12v8M18 12v8"/>',
  flash: '<path d="M13 2 4 14h7l-1 8 9-12h-7l1-8Z"/>',
  lock: '<rect x="4.5" y="10.5" width="15" height="10" rx="2.5"/><path d="M8 10.5V7.5a4 4 0 0 1 8 0v3"/><path d="M12 14.5v2.5"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M12 2.5v3M12 18.5v3M4.6 4.6l2.1 2.1M17.3 17.3l2.1 2.1M2.5 12h3M18.5 12h3M4.6 19.4l2.1-2.1M17.3 6.7l2.1-2.1"/>',
  backspace: '<path d="M9 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6-7 6-7Z"/><path d="M12.5 9.5l5 5M17.5 9.5l-5 5"/>',
  switchUser: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0"/><path d="M17 7h4.5M19.5 5l2 2-2 2M21.5 13H17M19 11l-2 2 2 2"/>',
  eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
  archive: '<rect x="3" y="4" width="18" height="5" rx="1"/><path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9M10 13h4"/>',
  sound: '<path d="M4 9v6h4l5 4V5L8 9H4Z"/><path d="M16.5 8.5a5 5 0 0 1 0 7M19 6a8.5 8.5 0 0 1 0 12"/>',
  mute: '<path d="M4 9v6h4l5 4V5L8 9H4Z"/><path d="m17 9 5 6M22 9l-5 6"/>',
};

const SHAPE = /<(path|rect|circle|ellipse)\s([^>]*?)\/>/g;
const ATTRIBUTE = /([a-z-]+)="([^"]*)"/g;
const parsed = new Map();

/**
 * Turns the static icon markup above into elements with createElementNS. The page never assigns HTML strings
 * (no innerHTML), so it can run with Trusted Types enforced, which blocks DOM-based XSS in the browser itself.
 */
function shapes(name) {
  if (!parsed.has(name)) {
    const markup = PATHS[name] ?? PATHS.info;
    parsed.set(name, [...markup.matchAll(SHAPE)].map(([, tag, attributes]) =>
      [tag, Object.fromEntries([...attributes.matchAll(ATTRIBUTE)].map(([, key, value]) => [key, value]))]));
  }
  return parsed.get(name);
}

/** Returns an inline SVG icon element. */
export function icon(name, className = '') {
  return s('svg', { viewBox: '0 0 24 24', class: `icon ${className}`.trim(), 'aria-hidden': 'true' },
    shapes(name).map(([tag, attributes]) => s(tag, attributes)));
}

/** The Sofra brand mark: a steaming bowl on a warm gradient tile. */
export function logoMark(className = 'logo-mark') {
  const id = `g${Math.random().toString(36).slice(2, 8)}`;
  return s('svg', { viewBox: '0 0 48 48', class: className, 'aria-hidden': 'true' },
    s('defs', null, s('linearGradient', { id, x1: '0', y1: '0', x2: '1', y2: '1' },
      s('stop', { offset: '0', 'stop-color': '#F6A54A' }),
      s('stop', { offset: '.55', 'stop-color': '#E4572E' }),
      s('stop', { offset: '1', 'stop-color': '#B8381A' }))),
    s('rect', { width: '48', height: '48', rx: '14', fill: `url(#${id})` }),
    s('path', { d: 'M10 23h28a14 14 0 0 1-28 0Z', fill: '#fff' }),
    s('rect', { x: '16', y: '37.5', width: '16', height: '2.6', rx: '1.3', fill: '#fff', opacity: '.85' }),
    s('path', {
      d: 'M19 18c0-2.2 2.2-2.2 2.2-4.4S19 11.4 19 9.2M26.5 18c0-2.2 2.2-2.2 2.2-4.4s-2.2-2.2-2.2-4.4',
      stroke: '#fff', 'stroke-width': '2.4', fill: 'none', 'stroke-linecap': 'round',
    }));
}
