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
export function connectLive({ onMessage, onStatus }) {
  let socket;
  let retry = 500;
  const open = () => {
    const url = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`;
    socket = new WebSocket(url);
    socket.onopen = () => { retry = 500; onStatus(true); };
    socket.onmessage = (event) => {
      try { onMessage(JSON.parse(event.data)); } catch (error) { console.error(error); }
    };
    socket.onclose = () => {
      onStatus(false);
      setTimeout(open, retry);
      retry = Math.min(retry * 2, 5000);
    };
    socket.onerror = () => socket.close();
  };
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
