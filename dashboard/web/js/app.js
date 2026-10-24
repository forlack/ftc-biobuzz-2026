// Total Chaos Dashboard: tabs, the OpMode bar and the live connection.
import { api, connectLive, escapeHtml, toast } from './api.js';
import { FrameDecoder } from './frames.js';
import { LiveTab } from './live.js';
import { TuneTab } from './tune.js';
import { RobotTab } from './robot.js';

const live = new LiveTab();
const tune = new TuneTab();
const robot = new RobotTab();
let state = null;
let tab = 'live';
let connection = null;   // the live websocket

function showTab(name) {
  tab = name;
  document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  document.querySelectorAll('.tab-page').forEach((p) => p.classList.toggle('active', p.id === `tab-${name}`));
  if (name === 'tune') tune.refresh();
  if (name === 'robot') robot.refresh(state ? state.hardwareMap : undefined);
  if (name === 'live') { live.resize(); live.dirty = true; }
  if (name === 'paths') loadVisualizer();
  try { localStorage.setItem('biobuzz.tab', name); } catch { /* private window */ }
  if (connection) subscribe();
}

async function loadVisualizer() {
  const frame = document.getElementById('visualizer');
  if (frame.src) return;
  const exists = await fetch(frame.dataset.src, { method: 'GET' }).then((r) => r.ok).catch(() => false);
  document.getElementById('visualizer-missing').hidden = exists;
  frame.hidden = !exists;
  if (exists) frame.src = frame.dataset.src;
}

document.querySelectorAll('.tabs button').forEach((b) => b.addEventListener('click', () => showTab(b.dataset.tab)));

// ---- OpMode bar ---------------------------------------------------------------
const select = document.getElementById('op-select');

function showState(next) {
  const first = !state;
  state = next;
  document.getElementById('robot-name').textContent = next.robot;
  document.getElementById('sim-badge').hidden = !next.simulated;
  const names = next.opModes.map((o) => o.name);
  if ([...select.options].map((o) => o.value).join('|') !== names.join('|')) {
    select.innerHTML = next.opModes.map((o) => `<option value="${escapeHtml(o.name)}">${escapeHtml(o.name)}</option>`).join('');
  }
  if (next.active && select.value !== next.active && document.activeElement !== select) select.value = next.active;
  const pill = document.getElementById('op-state');
  pill.textContent = next.active ? `${next.state}: ${next.active}` : 'stopped';
  pill.className = `pill ${next.state}`;
  const battery = document.getElementById('battery');
  battery.textContent = next.battery == null ? '— V' : `${next.battery.toFixed(2)} V`;
  battery.classList.toggle('low', next.battery != null && next.battery < 12.2);
  showLock(next.locked);
  showLoad(next.load);
  document.getElementById('op-start').disabled = next.state !== 'init';
  document.getElementById('op-stop').disabled = next.state === 'stopped';
  if (first && tab === 'robot') robot.refresh(next.hardwareMap);
  // The active map changed (Driver Station Activate, or Use this map): show it.
  if (!first && next.hardwareMap !== robot.hardwareMap) {
    robot.hardwareMap = next.hardwareMap;
    robot.hardware.setMaps(robot.hardware.maps, next.hardwareMap);
  }
}

function showLoad(load) {
  if (!load || load.viewers == null) return;
  // Live toolbar: the dashboard's own cost (drops when no browser is on the Live tab).
  document.getElementById('live-load').textContent =
    `Dashboard ${load.dashboardCpu ?? '—'}% CPU · ${load.kbPerSecond ?? '—'} KB/s`;
  const value = (v, unit) => (v == null ? '—' : `${v}${unit}`);
  const rows = [
    ['Viewers', `${load.viewers} (${load.liveViewers} on Live)`],
    ['Dashboard CPU', value(load.dashboardCpu, ' %')],
    ['Whole app CPU', value(load.appCpu, ' %')],
    ['Wi-Fi sent', value(load.kbPerSecond, ' KB/s')],
    ['OpMode loop', load.loopMs == null ? 'no OpMode running' : `${load.loopMs} ms (${load.loopsPerSecond}/s)`],
    ['Samples sent', load.maxSamplesPerSecond ? `at most ${load.maxSamplesPerSecond}/s` : 'every loop'],
  ];
  document.getElementById('load').innerHTML = rows.map(([k, v]) => `<tr><td>${k}</td><td>${escapeHtml(String(v))}</td></tr>`).join('');
}

async function opMode(action) {
  try {
    showState(await api('/api/opmode', { method: 'POST', body: { action, name: select.value } }));
  } catch (error) {
    toast(error.message, true);
  }
}
document.getElementById('op-init').addEventListener('click', () => opMode('init'));
document.getElementById('op-start').addEventListener('click', () => opMode('start'));
document.getElementById('op-stop').addEventListener('click', () => opMode('stop'));

// ---- Lock -----------------------------------------------------------------------------
let locked = false;

// Flat padlocks: shackle open (unlocked) or closed (locked).
const LOCK_ICON = {
  open: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 10V7a5 5 0 0 1 9.6-2" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/><rect x="4" y="10" width="16" height="11" rx="2.5" fill="currentColor"/><circle cx="12" cy="15.5" r="1.7" fill="var(--bg)"/></svg>',
  closed: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 10V7a5 5 0 0 1 10 0v3" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round"/><rect x="4" y="10" width="16" height="11" rx="2.5" fill="currentColor"/><circle cx="12" cy="15.5" r="1.7" fill="var(--bg)"/></svg>',
};

function showLock(value) {
  locked = !!value;
  document.body.classList.toggle('locked', locked);
  document.getElementById('locked-banner').hidden = !locked;
  const button = document.getElementById('lock');
  button.innerHTML = `${locked ? LOCK_ICON.closed : LOCK_ICON.open}<span>${locked ? 'Locked' : 'Unlocked'}</span>`;
  button.classList.toggle('on', locked);
  button.title = locked ? 'Locked: view only. Click and enter the PIN to unlock.'
    : 'Unlocked: anyone can make changes. Click to lock with a PIN.';
}
showLock(false);

// One PIN box, shown as you type, so there is nothing to type twice.
const lockDialog = document.getElementById('lock-dialog');
const pinInput = document.getElementById('lock-pin');

document.getElementById('lock').addEventListener('click', () => {
  document.getElementById('lock-title').textContent = locked ? 'Unlock the dashboard' : 'Lock the dashboard';
  document.getElementById('lock-hint').textContent = locked
    ? 'Enter the PIN to allow changes again.'
    : 'Anyone can still watch. Changing anything will need this PIN.';
  document.getElementById('lock-ok').textContent = locked ? 'Unlock' : 'Lock';
  document.getElementById('lock-error').textContent = '';
  pinInput.value = '';
  lockDialog.showModal();
  pinInput.focus();
});

pinInput.addEventListener('input', () => { pinInput.value = pinInput.value.replace(/\D/g, ''); });

lockDialog.addEventListener('close', async () => {
  if (lockDialog.returnValue !== 'ok') return;
  const pin = pinInput.value;
  try {
    const result = await api('/api/lock', { method: 'POST', body: { action: locked ? 'unlock' : 'lock', pin } });
    showLock(result.locked);
    toast(result.locked ? `Locked with PIN ${pin}: view only` : 'Unlocked: changes allowed');
  } catch (error) {
    toast(error.message, true);
  }
});

// History buttons elsewhere (Tune, the path editor) open the History panel on the Robot tab.
window.addEventListener('biobuzz:history', (event) => {
  showTab('robot');
  robot.history.open(event.detail);
});

// ---- Live connection ----------------------------------------------------------------
const decoder = new FrameDecoder();
connection = connectLive({
  onStatus(up) {
    const conn = document.getElementById('conn');
    conn.classList.toggle('up', up);
    conn.querySelector('span').textContent = up ? 'live' : 'offline';
    if (up) subscribe();
  },
  onMessage(message) {
    if (message.type === 'state') { showState(message); return; }
    const telemetry = decoder.handle(message);
    if (!telemetry) return;
    if (telemetry.restarted) live.reset();
    if (telemetry.frames.length) live.addFrames(telemetry.frames);
  },
});

/** Loop data is only sent while the Live tab is showing; other tabs get status only. */
function subscribe() {
  connection.send({ type: 'subscribe', frames: tab === 'live' && !document.hidden });
}
document.addEventListener('visibilitychange', subscribe);

// The path editor (in its iframe) can ask for things it cannot reach itself.
window.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'biobuzz:saved') toast(`Saved ${event.data.path} on the robot`);
  if (event.data && event.data.type === 'biobuzz:history') window.dispatchEvent(new CustomEvent('biobuzz:history', { detail: event.data.path }));
});

let saved = 'live';
try { saved = localStorage.getItem('biobuzz.tab') || 'live'; } catch { /* private window */ }
showTab(saved);
