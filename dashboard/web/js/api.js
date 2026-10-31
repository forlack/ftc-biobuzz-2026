// Talking to the robot (or the simulator): JSON API, raw files, the live websocket.

export async function api(path, { method = 'GET', body, headers = {} } = {}) {
  const options = { method, headers: { 'X-Client-Time': String(Date.now()), ...headers } };
  if (body !== undefined) {
    options.body = typeof body === 'string' ? body : JSON.stringify(body);
    if (typeof body !== 'string') options.headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(path, options);
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!response.ok) {
    const message = data && data.error ? data.error : `${response.status} ${response.statusText}`;
    const error = new Error(message);
    error.status = response.status;
    throw error;
  }
  return data;
}

/** Raw file from the robot, with its hash so a later save can detect someone else's change. */
export async function getFile(path) {
  const response = await fetch(`/api/file?path=${encodeURIComponent(path)}`, { headers: { 'X-Client-Time': String(Date.now()) } });
  if (!response.ok) throw new Error((await response.json()).error);
  return { text: await response.text(), hash: response.headers.get('X-Hash') };
}

/** Saves a file on the robot. baseHash: the hash it had when opened, "new", or "*" to overwrite. */
export function putFile(path, text, baseHash = '*') {
  return api(`/api/file?path=${encodeURIComponent(path)}`, {
    method: 'PUT', body: text, headers: { 'X-Base-Hash': baseHash },
  });
}

/** Keeps a websocket open, reconnecting after drops. Calls onMessage(json) and onStatus(bool); returns {send}. */
/**
 * The live connection to the robot, kept up by itself. The robot sends its state twice a
 * second, so 3 s of silence means the link is dead even when the browser has not noticed (after
 * a Wi-Fi drop the socket often just goes quiet): then it is dropped and a new one opened. It
 * also reconnects straight away when the computer comes back online.
 */
export function connectLive({ onMessage, onStatus }) {
  const SILENT_MS = 3000;
  let socket;
  let retry = 500;
  let lastMessage = 0;
  let timer = null;
  const reopenLater = () => {
    clearTimeout(timer);
    timer = setTimeout(open, retry);
    retry = Math.min(retry * 2, 5000);
  };
  const open = () => {
    clearTimeout(timer);
    const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
    const mine = new WebSocket(url);
    socket = mine;
    lastMessage = Date.now();
    mine.onopen = () => {
      if (socket !== mine) return;
      retry = 500;
      lastMessage = Date.now();
      onStatus(true);
    };
    mine.onmessage = (event) => {
      if (socket !== mine) return;
      lastMessage = Date.now();
      try { onMessage(JSON.parse(event.data)); } catch (error) { console.error(error); }
    };
    mine.onclose = () => {
      if (socket !== mine) return;   // already replaced (the silence check)
      onStatus(false);
      reopenLater();
    };
    mine.onerror = () => mine.close();
  };
  const restart = () => {
    const old = socket;
    socket = null;
    if (old) {
      old.onclose = null;
      try { old.close(); } catch { /* already gone */ }
    }
    onStatus(false);
    open();
  };
  setInterval(() => {
    if (!socket) return;
    const quiet = Date.now() - lastMessage;
    // Open but silent, or still trying to connect after 5 s: start over.
    if ((socket.readyState === WebSocket.OPEN && quiet > SILENT_MS)
        || (socket.readyState === WebSocket.CONNECTING && quiet > 5000)) {
      restart();
    }
  }, 1000);
  window.addEventListener('online', () => {
    if (!socket || socket.readyState !== WebSocket.OPEN || Date.now() - lastMessage > 1000) restart();
  });
  open();
  return {
    send(message) {
      if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
    },
  };
}

export function toast(message, bad = false) {
  const element = document.getElementById('toast');
  element.textContent = message;
  element.classList.toggle('bad', bad);
  element.hidden = false;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { element.hidden = true; }, bad ? 6000 : 2500);
}

export function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/** A date the robot sent, for people: "date unknown" for a 1970 one (the robot's clock was unset). */
export function formatTime(ms) {
  return ms >= 1577836800000 ? new Date(ms).toLocaleString() : 'date unknown';
}
