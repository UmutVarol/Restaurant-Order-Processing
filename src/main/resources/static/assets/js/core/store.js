// Application-wide event bus and user preferences.

const target = new EventTarget();

export const bus = {
  on(type, handler) {
    const listener = (event) => handler(event.detail);
    target.addEventListener(type, listener);
    return () => target.removeEventListener(type, listener);
  },
  emit(type, detail) {
    target.dispatchEvent(new CustomEvent(type, { detail }));
  },
};

function read(key, fallback) {
  try {
    const value = localStorage.getItem(`sofra.${key}`);
    return value == null ? fallback : JSON.parse(value);
  } catch {
    return fallback;
  }
}

function write(key, value) {
  try {
    localStorage.setItem(`sofra.${key}`, JSON.stringify(value));
  } catch { /* storage unavailable — preference lasts for this page only */ }
}

export const prefs = {
  get theme() {
    return document.documentElement.dataset.theme || 'light';
  },
  set theme(value) {
    document.documentElement.dataset.theme = value;
    write('theme', value);
    bus.emit('theme', value);
  },
  get sound() {
    return read('sound', false);
  },
  set sound(value) {
    write('sound', value);
  },
};

/** A short, friendly two-tone chime for new kitchen tickets (Web Audio, no asset needed). */
export function chime() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    [880, 1318.5].forEach((frequency, index) => {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = 'sine';
      osc.frequency.value = frequency;
      const start = ctx.currentTime + index * 0.16;
      gain.gain.setValueAtTime(0.0001, start);
      gain.gain.exponentialRampToValueAtTime(0.18, start + 0.02);
      gain.gain.exponentialRampToValueAtTime(0.0001, start + 0.45);
      osc.connect(gain).connect(ctx.destination);
      osc.start(start);
      osc.stop(start + 0.5);
    });
    setTimeout(() => ctx.close(), 1200);
  } catch { /* audio not available */ }
}
