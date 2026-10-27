// QR codes drawn as SVG paths (never as HTML strings), so they stay sharp on any screen and print well.
import { s } from '../core/dom.js';
import qrcode from '../vendor/qrcode.mjs';

const QUIET_ZONE = 4;

export function qrCode(text, { label = text } = {}) {
  const qr = qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  const size = qr.getModuleCount();
  let path = '';
  for (let row = 0; row < size; row += 1) {
    for (let col = 0; col < size; col += 1) {
      if (qr.isDark(row, col)) path += `M${col} ${row}h1v1h-1z`;
    }
  }
  const box = size + QUIET_ZONE * 2;
  return s('svg', { class: 'qr', viewBox: `${-QUIET_ZONE} ${-QUIET_ZONE} ${box} ${box}`, role: 'img', 'aria-label': label,
    'shape-rendering': 'crispEdges', 'data-testid': 'qr-code' },
  s('rect', { x: -QUIET_ZONE, y: -QUIET_ZONE, width: box, height: box, fill: '#fff' }),
  s('path', { d: path, fill: '#1f1a17' }));
}
