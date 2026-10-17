// Dependency-free charts. Plots are SVG stretched to their container; labels, dots and tooltips are HTML so
// text stays crisp at any width.
import { h, s } from './dom.js';

let gradientSeq = 0;

function niceMax(value) {
  if (value <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 2.5, 5, 10].find((m) => m * magnitude >= value / 4 * 1.0001) * magnitude;
  return Math.ceil(value / step) * step;
}

/** Smooth path through points using a Catmull–Rom spline converted to cubic Béziers. */
function smoothPath(pts) {
  if (pts.length < 2) return pts.length ? `M${pts[0][0]},${pts[0][1]}` : '';
  let d = `M${pts[0][0]},${pts[0][1]}`;
  for (let i = 0; i < pts.length - 1; i++) {
    const [x0, y0] = pts[Math.max(0, i - 1)];
    const [x1, y1] = pts[i];
    const [x2, y2] = pts[i + 1];
    const [x3, y3] = pts[Math.min(pts.length - 1, i + 2)];
    const tension = 0.18;
    const c1 = [x1 + (x2 - x0) * tension, y1 + (y2 - y0) * tension];
    const c2 = [x2 - (x3 - x1) * tension, y2 - (y3 - y1) * tension];
    d += ` C${c1[0]},${Math.min(100, c1[1])} ${c2[0]},${Math.min(100, c2[1])} ${x2},${y2}`;
  }
  return d;
}

/**
 * Area chart with hover tooltip.
 * @param {{label: string, value: number, tip?: string, sub?: string}[]} points
 */
export function areaChart(points, { height = 240, format = String, color = 'var(--brand)' } = {}) {
  const max = niceMax(Math.max(0, ...points.map((p) => p.value)));
  const n = points.length;
  const xAt = (i) => (n === 1 ? 50 : 2 + (i * 96) / (n - 1));
  const yAt = (v) => 100 - (v / max) * 92;
  const coords = points.map((p, i) => [xAt(i) * 10, yAt(p.value)]);
  const id = `area${++gradientSeq}`;

  const svg = s('svg', { class: 'plot', viewBox: '0 0 1000 100', preserveAspectRatio: 'none' },
    s('defs', null, s('linearGradient', { id, x1: 0, y1: 0, x2: 0, y2: 1 },
      s('stop', { offset: '0', 'stop-color': color, 'stop-opacity': '.28' }),
      s('stop', { offset: '1', 'stop-color': color, 'stop-opacity': '0' }))),
    [0, 0.25, 0.5, 0.75, 1].map((f) => s('line', {
      class: 'grid-line', x1: 0, x2: 1000, y1: yAt(max * f), y2: yAt(max * f), 'vector-effect': 'non-scaling-stroke',
    })),
    s('path', { d: `${smoothPath(coords)} L${coords.at(-1)[0]},100 L${coords[0][0]},100 Z`, fill: `url(#${id})` }),
    s('path', { d: smoothPath(coords), class: 'area-line', style: `stroke:${color}`, 'vector-effect': 'non-scaling-stroke' }));

  const dot = h('div', { class: 'hover-dot', style: { borderColor: color } });
  const tip = h('div', { class: 'chart-tip' });
  const cross = h('div', { class: 'crosshair' });
  const plot = h('div', { class: 'plot-area' }, svg, cross, dot, tip);
  const every = Math.ceil(n / 10);
  const showLabel = (i) => i === n - 1 || (i % every === 0 && n - 1 - i >= every * 0.6);
  const xAxis = h('div', { class: 'x-axis' }, points.map((p, i) => (showLabel(i)
    ? h('span', { style: { left: `${xAt(i)}%` } }, p.label) : null)));
  const yAxis = h('div', { class: 'y-axis' }, [0.5, 1].map((f) => h('span', { style: { top: `${yAt(max * f)}%` } }, format(max * f))));

  plot.addEventListener('pointermove', (event) => {
    const rect = plot.getBoundingClientRect();
    const ratio = (event.clientX - rect.left) / rect.width * 100;
    const index = Math.max(0, Math.min(n - 1, Math.round(((ratio - 2) / 96) * (n - 1))));
    const point = points[index];
    const left = `${xAt(index)}%`;
    const top = `${yAt(point.value)}%`;
    Object.assign(dot.style, { left, top, opacity: 1 });
    Object.assign(cross.style, { left, opacity: 1 });
    Object.assign(tip.style, { left, top });
    tip.replaceChildren(h('b', null, point.tip ?? format(point.value)), point.sub ? h('span', null, point.sub) : null);
    tip.classList.add('show');
  });
  plot.addEventListener('pointerleave', () => {
    dot.style.opacity = 0;
    cross.style.opacity = 0;
    tip.classList.remove('show');
  });

  return h('div', { class: 'chart area', style: { height: `${height}px` } }, plot, yAxis, xAxis);
}

/** Vertical bar chart; the tallest bars are highlighted. */
export function barChart(bars, { height = 190, format = String } = {}) {
  const max = Math.max(1, ...bars.map((b) => b.value));
  const threshold = [...bars].map((b) => b.value).sort((a, b) => b - a)[Math.min(2, bars.length - 1)] ?? max;
  const tip = h('div', { class: 'chart-tip' });
  const wrap = h('div', { class: 'chart bars', style: { height: `${height}px` } },
    bars.map((bar) => {
      const column = h('div', { class: ['b', bar.value >= threshold && bar.value > 0 ? 'hot' : ''] },
        h('i', { style: { height: `${Math.max(2, (bar.value / max) * 100)}%` } }),
        h('span', null, bar.label));
      column.addEventListener('pointerenter', () => {
        const rect = column.getBoundingClientRect();
        const host = wrap.getBoundingClientRect();
        tip.style.left = `${rect.left - host.left + rect.width / 2}px`;
        tip.style.top = `${rect.bottom - host.top - 22 - (bar.value / max) * (height - 22)}px`;
        tip.replaceChildren(h('b', null, bar.tip ?? format(bar.value)), bar.sub ? h('span', null, bar.sub) : null);
        tip.classList.add('show');
      });
      column.addEventListener('pointerleave', () => tip.classList.remove('show'));
      return column;
    }), tip);
  return wrap;
}

/** Donut chart with a centre label. */
export function donutChart(slices, { size = 160, thickness = 22, center } = {}) {
  const total = slices.reduce((sum, slice) => sum + slice.value, 0) || 1;
  const r = 50 - thickness / 2 - 1;
  const circumference = 2 * Math.PI * r;
  let offset = 0;
  const gap = slices.filter((x) => x.value > 0).length > 1 ? 1.2 : 0;
  const svg = s('svg', { viewBox: '0 0 100 100', width: size, height: size, style: 'transform: rotate(-90deg)' },
    s('circle', { cx: 50, cy: 50, r, fill: 'none', stroke: 'var(--surface-3)', 'stroke-width': thickness }),
    slices.map((slice) => {
      const length = (slice.value / total) * circumference;
      const segment = s('circle', {
        cx: 50, cy: 50, r, fill: 'none', stroke: slice.color, 'stroke-width': thickness,
        'stroke-dasharray': `${Math.max(0, length - gap)} ${circumference}`, 'stroke-dashoffset': -offset,
        'stroke-linecap': 'butt',
      }, s('title', null, slice.label));
      offset += length;
      return segment;
    }));
  return h('div', { style: { position: 'relative', width: `${size}px`, height: `${size}px` } }, svg,
    center ? h('div', { class: 'donut-label' }, h('b', { class: 'donut-center' }, center.value), h('span', null, center.label)) : null);
}

/** Tiny decorative trend line for KPI tiles. */
export function sparkline(values, color = 'var(--brand)') {
  if (values.length < 2) return null;
  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const pts = values.map((v, i) => [(i / (values.length - 1)) * 100, 92 - ((v - min) / (max - min || 1)) * 78]);
  const id = `spark${++gradientSeq}`;
  return s('svg', { class: 'spark', viewBox: '0 0 100 100', preserveAspectRatio: 'none' },
    s('defs', null, s('linearGradient', { id, x1: 0, y1: 0, x2: 0, y2: 1 },
      s('stop', { offset: '0', 'stop-color': color, 'stop-opacity': '.22' }),
      s('stop', { offset: '1', 'stop-color': color, 'stop-opacity': '0' }))),
    s('path', { d: `${smoothPath(pts)} L100,100 L0,100 Z`, fill: `url(#${id})` }),
    s('path', { d: smoothPath(pts), fill: 'none', stroke: color, 'stroke-width': 2, 'vector-effect': 'non-scaling-stroke', opacity: '.8' }));
}
