// BioBuzz dashboard: tabs, the OpMode bar and the live connection.
import { api, connectLive, escapeHtml, toast } from './api.js';
import { LiveTab } from './live.js';
import { TuneTab } from './tune.js';
import { RobotTab } from './robot.js';

const live = new LiveTab();
const tune = new TuneTab();
const robot = new RobotTab();
let state = null;
let tab = 'live';

function showTab(name) {
  tab = name;
  document.querySelectorAll('.tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  document.querySelectorAll('.tab-page').forEach((p) => p.classList.toggle('active', p.id === `tab-${name}`));
  if (name === 'tune') tune.refresh();
  if (name === 'robot') robot.refresh(state ? state.hardwareMap : undefined);
  if (name === 'live') { live.resize(); live.dirty = true; }
  if (name === 'paths') loadVisualizer();
  try { localStorage.setItem('biobuzz.tab', name); } catch { /* private window */ }
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
  document.getElementById('op-start').disabled = next.state !== 'init';
  document.getElementById('op-stop').disabled = next.state === 'stopped';
  if (first && tab === 'robot') robot.refresh(next.hardwareMap);
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

// ---- Live connection ----------------------------------------------------------------
connectLive({
  onStatus(up) {
    const conn = document.getElementById('conn');
    conn.classList.toggle('up', up);
    conn.querySelector('span').textContent = up ? 'live' : 'offline';
  },
  onMessage(message) {
    if (message.type === 'frames') live.addFrames(message.frames);
    else if (message.type === 'state') showState(message);
  },
});

// The path editor (in its iframe) can ask for things it cannot reach itself.
window.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'biobuzz:saved') toast(`Saved ${event.data.path} on the robot`);
});

let saved = 'live';
try { saved = localStorage.getItem('biobuzz.tab') || 'live'; } catch { /* private window */ }
showTab(saved);
