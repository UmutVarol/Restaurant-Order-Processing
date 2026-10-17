// Live order updates over Server-Sent Events. EventSource cannot send an Authorization header, so the stream is
// read with fetch() and parsed here; the connection is re-established with exponential back-off.
import { authHeader, backendFetch, currentSession, EVENTS_URL, isCurrentSession } from './api.js';
import { bus } from './store.js';

let controller = null;
let status = 'off';

export function realtimeStatus() {
  return status;
}

function setStatus(next) {
  if (next === status) return;
  status = next;
  bus.emit('realtime:status', status);
}

export function startRealtime() {
  stopRealtime();
  controller = new AbortController();
  run(controller.signal);
}

export function stopRealtime() {
  controller?.abort();
  controller = null;
  setStatus('off');
}

async function run(signal) {
  let attempt = 0;
  while (!signal.aborted) {
    const sentWith = currentSession();
    try {
      const response = await backendFetch(EVENTS_URL, {
        headers: { Accept: 'text/event-stream', ...authHeader() },
        cache: 'no-store',
        signal,
      });
      if (response.status === 401) {
        if (isCurrentSession(sentWith)) bus.emit('session:expired');
        return;
      }
      if (!response.ok || !response.body) throw new Error(`stream status ${response.status}`);
      attempt = 0;
      setStatus('on');
      await consume(response.body, signal);
    } catch {
      if (signal.aborted) return;
    }
    setStatus('off');
    await wait(Math.min(1000 * 2 ** attempt++, 15_000), signal);
  }
}

async function consume(body, signal) {
  const reader = body.pipeThrough(new TextDecoderStream()).getReader();
  signal.addEventListener('abort', () => reader.cancel().catch(() => {}), { once: true });
  let buffer = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return;
    buffer += value;
    let boundary;
    while ((boundary = buffer.search(/\r?\n\r?\n/)) >= 0) {
      const block = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary).replace(/^\r?\n\r?\n/, '');
      dispatch(parse(block));
    }
  }
}

function parse(block) {
  const event = { type: 'message', data: [] };
  for (const line of block.split(/\r?\n/)) {
    if (!line || line.startsWith(':')) continue;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event.type = value;
    else if (field === 'data') event.data.push(value);
  }
  return event;
}

function dispatch(event) {
  if (event.type !== 'order' || event.data.length === 0) return;
  try {
    bus.emit('order', JSON.parse(event.data.join('\n')));
  } catch { /* ignore malformed event */ }
}

function wait(ms, signal) {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => { clearTimeout(timer); resolve(); }, { once: true });
  });
}
