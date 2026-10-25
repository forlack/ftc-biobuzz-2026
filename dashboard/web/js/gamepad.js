// The Gamepad drawer: a virtual gamepad 1 or 2 at the bottom of every tab. Drive with the
// on-screen controller (mouse or touch) or a controller plugged into this computer. Practice
// only. The robot side is VirtualGamepads.java; this only sends inputs after Take control,
// 20 times a second, and the robot zeroes them if they stop for 0.25 s.
import { escapeHtml, toast } from './api.js';

// Same orders as VirtualGamepads.BUTTONS / AXES.
const BUTTONS = ['a', 'b', 'x', 'y', 'lb', 'rb', 'up', 'down', 'left', 'right', 'back', 'start', 'guide', 'ls', 'rs'];
const BIT = Object.fromEntries(BUTTONS.map((name, i) => [name, 1 << i]));
const LX = 0, LY = 1, RX = 2, RY = 3, LT = 4, RT = 5;
// The browser's "standard" controller layout: button index -> our name.
const STANDARD = ['a', 'b', 'x', 'y', 'lb', 'rb', null, null, 'back', 'start', 'ls', 'rs', 'up', 'down', 'left', 'right', 'guide'];
const SEND_MS = 50;
const DEADZONE = 0.05;
const STICK_TRAVEL = 22;   // svg units the knob moves at full stick

// What each control does in Field Centric (Java), from its header comment.
const LEGEND = {
  1: [['Left stick', 'drive (field-centric)'], ['Right stick ←→', 'turn'], ['LB', 'hold: slow'],
    ['RB', 'hold: turbo'], ['B', 'reset forward'], ['A + RB', 'hold: force shoot']],
  2: [['X', 'intake on / off'], ['D-pad ↓', 'hold: reverse intake'], ['A', 'flywheels on / off'],
    ['RB', 'hold: gates open'], ['LB', 'hold: stinger down']],
};

// PS5 / Logitech F310 layout: D-pad and A B X Y on top, the two sticks side by side below.
const SVG = `
<svg class="pad-svg" id="pad-svg" viewBox="0 0 520 270" aria-label="Virtual gamepad">
  <rect class="pad-key trigger" data-axis="lt" x="80" y="6" width="100" height="20" rx="6"/><text x="130" y="21">LT</text>
  <rect class="pad-key trigger" data-axis="rt" x="340" y="6" width="100" height="20" rx="6"/><text x="390" y="21">RT</text>
  <rect class="pad-key" data-btn="lb" x="70" y="32" width="118" height="22" rx="10"/><text x="129" y="48">LB</text>
  <rect class="pad-key" data-btn="rb" x="332" y="32" width="118" height="22" rx="10"/><text x="391" y="48">RB</text>
  <path class="pad-body" d="M120 60 H400 C450 60 480 90 492 140 C505 195 510 245 470 255 C440 262 418 248 398 238 H122 C102 248 80 262 50 255 C10 245 15 195 28 140 C40 90 70 60 120 60 Z"/>
  <rect class="pad-key" data-btn="up" x="114" y="85" width="22" height="24" rx="3"/>
  <rect class="pad-key" data-btn="down" x="114" y="131" width="22" height="24" rx="3"/>
  <rect class="pad-key" data-btn="left" x="90" y="109" width="24" height="22" rx="3"/>
  <rect class="pad-key" data-btn="right" x="136" y="109" width="24" height="22" rx="3"/>
  <rect class="pad-center" x="114" y="109" width="22" height="22"/>
  <text class="tiny" x="226" y="90">BACK</text><rect class="pad-key" data-btn="back" x="212" y="96" width="28" height="14" rx="7"/>
  <text class="tiny" x="294" y="90">START</text><rect class="pad-key" data-btn="start" x="280" y="96" width="28" height="14" rx="7"/>
  <circle class="pad-key" data-btn="guide" cx="260" cy="132" r="11"/>
  <circle class="pad-key face y" data-btn="y" cx="395" cy="88" r="15"/><text x="395" y="93">Y</text>
  <circle class="pad-key face x" data-btn="x" cx="363" cy="120" r="15"/><text x="363" y="125">X</text>
  <circle class="pad-key face b" data-btn="b" cx="427" cy="120" r="15"/><text x="427" y="125">B</text>
  <circle class="pad-key face a" data-btn="a" cx="395" cy="152" r="15"/><text x="395" y="157">A</text>
  <g class="pad-stick" data-stick="left"><circle class="well" cx="200" cy="190" r="40"/><circle class="knob" cx="200" cy="190" r="21"/><text class="small" x="200" y="194">L</text></g>
  <g class="pad-stick" data-stick="right"><circle class="well" cx="320" cy="190" r="40"/><circle class="knob" cx="320" cy="190" r="21"/><text class="small" x="320" y="194">R</text></g>
</svg>`;

export class GamepadDrawer {
  constructor({ send, onOpenChange }) {
    this.send = send;
    this.onOpenChange = onOpenChange;
    this.open = false;
    this.pad = 1;
    this.id = 0;              // this browser's connection id, from the robot's hello
    this.owners = [0, 0];     // who controls gamepads 1 and 2
    this.active = '';         // the active OpMode
    this.locked = false;
    this.robot = null;        // the last {pads} from the robot
    this.touchAxes = [0, 0, 0, 0, 0, 0];
    this.touchButtons = 0;
    this.held = 0;            // buttons held by a right-click
    this.timer = null;
    this.waiting = false;     // asked for control, no answer yet

    this.root = document.getElementById('pad-drawer');
    this.handle = document.getElementById('pad-handle');
    this.body = document.getElementById('pad-body');
    document.getElementById('pad-art').innerHTML = SVG;
    this.svg = document.getElementById('pad-svg');
    this.take = document.getElementById('pad-take');
    this.pill = document.getElementById('pad-pill');

    try {
      const saved = JSON.parse(localStorage.getItem('biobuzz.gamepad') || '{}');
      if (saved.pad === 2) this.pad = 2;
      if (saved.open) this.setOpen(true, false);
    } catch { /* private window */ }

    this.handle.addEventListener('click', () => this.setOpen(!this.open));
    this.take.addEventListener('click', () => (this.controlling() ? this.release() : this.requestControl()));
    this.pill.addEventListener('click', () => this.release());
    document.querySelectorAll('#pad-seg button').forEach((button) => button.addEventListener('click', () => {
      const pad = Number(button.dataset.pad);
      if (pad === this.pad) return;
      if (this.controlling()) this.release();
      this.pad = pad;
      this.remember();
      this.show();
    }));
    document.addEventListener('keydown', (event) => {
      if (event.key !== 'g' && event.key !== 'G') return;
      if (event.ctrlKey || event.metaKey || event.altKey) return;
      const target = event.target;
      if (target.closest && target.closest('input, textarea, select, [contenteditable="true"], dialog')) return;
      this.setOpen(!this.open);
    });
    // A hidden tab gets its timers slowed to once a second: stop driving rather than lurch.
    document.addEventListener('visibilitychange', () => {
      if (document.hidden && this.controlling()) this.release('Gamepad released: the tab was hidden.');
    });
    window.addEventListener('gamepadconnected', () => this.show());
    window.addEventListener('gamepaddisconnected', () => this.show());
    this.bindPointer();
    this.show();
  }

  controlling() {
    return this.id !== 0 && this.owners[this.pad - 1] === this.id;
  }

  setOpen(open, remember = true) {
    this.open = open;
    this.body.hidden = !open;
    this.root.classList.toggle('open', open);
    this.handle.setAttribute('aria-expanded', String(open));
    this.handle.querySelector('.chev').textContent = open ? '▼' : '▲';
    this.handle.title = `${open ? 'Hide' : 'Show'} the virtual gamepad (G)`;
    if (remember) this.remember();
    if (this.onOpenChange) this.onOpenChange(open);
    this.show();
  }

  remember() {
    try { localStorage.setItem('biobuzz.gamepad', JSON.stringify({ open: this.open, pad: this.pad })); } catch { /* private window */ }
  }

  // ---- Messages from the robot ----------------------------------------------------

  hello(id) {
    this.id = id;   // a new connection: any control we had ended with the old one
    this.stopSending();
    this.show();
  }

  state(state) {
    const was = this.controlling();
    this.owners = state.gamepads || [0, 0];
    this.active = state.active || '';
    this.locked = !!state.locked;
    if (was && !this.controlling()) {
      this.stopSending();
      toast(!this.active ? 'Gamepad control ended: the OpMode stopped.'
        : this.locked ? 'Gamepad control ended: the dashboard was locked.' : 'Gamepad control ended.', true);
    }
    this.show();
  }

  control(reply) {
    this.waiting = false;
    this.owners[reply.pad - 1] = reply.owner;
    if (reply.error) toast(reply.error, true);
    if (this.controlling()) this.startSending(); else this.stopSending();
    this.show();
  }

  gamepads(message) {
    this.robot = message.pads;
    if (!this.controlling()) this.render();
  }

  // ---- Control -----------------------------------------------------------------------

  requestControl() {
    if (!this.active) { toast('Init an OpMode first: the gamepad only works while one runs.', true); return; }
    this.waiting = true;
    this.send({ type: 'control', pad: this.pad, take: true });
    this.show();
  }

  release(message) {
    if (!this.controlling()) return;
    this.send({ type: 'control', pad: this.pad, take: false });
    this.owners[this.pad - 1] = 0;
    this.stopSending();
    if (message) toast(message);
    this.show();
  }

  startSending() {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), SEND_MS);
    this.tick();
  }

  stopSending() {
    clearInterval(this.timer);
    this.timer = null;
    this.touchAxes = [0, 0, 0, 0, 0, 0];
    this.touchButtons = 0;
    this.held = 0;
    this.render();
  }

  tick() {
    if (!this.controlling()) { this.stopSending(); return; }
    const input = this.inputs();
    this.send({ type: 'gamepad', pad: this.pad, axes: input.axes.map((v) => Math.round(v * 1000) / 1000), buttons: input.buttons });
    this.render(input);
  }

  /** The on-screen controller and the plugged-in one together: stronger stick, either button. */
  inputs() {
    const axes = [...this.touchAxes];
    let buttons = this.touchButtons | this.held;
    const device = this.device();
    if (device) {
      const stick = (i) => (Math.abs(device.axes[i] || 0) < DEADZONE ? 0 : device.axes[i]);
      const fromDevice = [stick(0), stick(1), stick(2), stick(3),
        device.buttons[6] ? device.buttons[6].value : 0, device.buttons[7] ? device.buttons[7].value : 0];
      fromDevice.forEach((value, i) => { if (Math.abs(value) > Math.abs(axes[i])) axes[i] = value; });
      STANDARD.forEach((name, i) => { if (name && device.buttons[i] && device.buttons[i].pressed) buttons |= BIT[name]; });
    }
    return { axes, buttons };
  }

  /** The first controller plugged into this computer, or null. */
  device() {
    if (!navigator.getGamepads) return null;
    for (const pad of navigator.getGamepads()) if (pad && pad.connected) return pad;
    return null;
  }

  // ---- On-screen controller --------------------------------------------------------

  bindPointer() {
    const toSvg = (event) => {
      const point = this.svg.createSVGPoint();
      point.x = event.clientX;
      point.y = event.clientY;
      return point.matrixTransform(this.svg.getScreenCTM().inverse());
    };
    this.svg.addEventListener('contextmenu', (event) => event.preventDefault());
    this.svg.addEventListener('pointerdown', (event) => {
      if (!this.controlling()) return;
      const stick = event.target.closest('[data-stick]');
      const key = event.target.closest('[data-btn], [data-axis]');
      if (!stick && !key) return;
      event.preventDefault();
      if (key && event.button === 2) {   // right-click: hold a button down until clicked again
        if (key.dataset.btn) this.held ^= BIT[key.dataset.btn];
        return;
      }
      const element = stick || key;
      element.setPointerCapture(event.pointerId);
      const well = stick ? stick.querySelector('.well') : null;
      const centre = well ? { x: Number(well.getAttribute('cx')), y: Number(well.getAttribute('cy')) } : null;
      const move = (e) => {
        if (!stick) return;
        const p = toSvg(e);
        let x = (p.x - centre.x) / STICK_TRAVEL;
        let y = (p.y - centre.y) / STICK_TRAVEL;
        const length = Math.hypot(x, y);
        if (length > 1) { x /= length; y /= length; }
        const base = stick.dataset.stick === 'left' ? LX : RX;
        this.touchAxes[base] = x;
        this.touchAxes[base + 1] = y;   // down is positive, like the FTC gamepad
      };
      const down = () => {
        if (key && key.dataset.btn) this.touchButtons |= BIT[key.dataset.btn];
        if (key && key.dataset.axis) this.touchAxes[key.dataset.axis === 'lt' ? LT : RT] = 1;
      };
      const up = () => {
        element.removeEventListener('pointermove', move);
        element.removeEventListener('pointerup', up);
        element.removeEventListener('pointercancel', up);
        if (stick) {   // sticks spring back to the middle
          const base = stick.dataset.stick === 'left' ? LX : RX;
          this.touchAxes[base] = 0;
          this.touchAxes[base + 1] = 0;
        }
        if (key && key.dataset.btn) this.touchButtons &= ~BIT[key.dataset.btn];
        if (key && key.dataset.axis) this.touchAxes[key.dataset.axis === 'lt' ? LT : RT] = 0;
      };
      element.addEventListener('pointermove', move);
      element.addEventListener('pointerup', up);
      element.addEventListener('pointercancel', up);
      down();
      move(event);
    });
  }

  /** Draws a gamepad state: this browser's inputs while driving, otherwise the robot's view. */
  render(input) {
    let state = input;
    if (!state && this.controlling()) state = this.inputs();
    if (!state) {
      const pad = this.robot && this.robot[this.pad - 1];
      state = pad ? { axes: pad.axes, buttons: pad.buttons } : { axes: [0, 0, 0, 0, 0, 0], buttons: 0 };
    }
    for (const stick of this.svg.querySelectorAll('[data-stick]')) {
      const base = stick.dataset.stick === 'left' ? LX : RX;
      const x = state.axes[base] || 0, y = state.axes[base + 1] || 0;
      stick.querySelectorAll('.knob, text').forEach((el) => el.setAttribute('transform', `translate(${x * STICK_TRAVEL} ${y * STICK_TRAVEL})`));
      stick.classList.toggle('on', Math.hypot(x, y) > 0.05);
      stick.classList.toggle('pressed', !!(state.buttons & BIT[base === LX ? 'ls' : 'rs']));
    }
    for (const key of this.svg.querySelectorAll('[data-btn]')) {
      key.classList.toggle('on', !!(state.buttons & BIT[key.dataset.btn]));
      key.classList.toggle('held', !!(this.held & BIT[key.dataset.btn]));
    }
    for (const key of this.svg.querySelectorAll('[data-axis]')) {
      key.classList.toggle('on', (state.axes[key.dataset.axis === 'lt' ? LT : RT] || 0) > 0.05);
    }
    const f = (v) => (v || 0).toFixed(2).replace(/^-?0\.00$/, '0.00');
    document.getElementById('pad-readout').textContent =
      `left ${f(state.axes[LX])}, ${f(state.axes[LY])}   right ${f(state.axes[RX])}, ${f(state.axes[RY])}   LT ${f(state.axes[LT])}  RT ${f(state.axes[RT])}`;
  }

  /** Everything that is not the controller picture: buttons, status, handle, pill. */
  show() {
    const mine = this.controlling();
    const owner = this.owners[this.pad - 1];
    const device = this.device();
    document.querySelectorAll('#pad-seg button').forEach((b) => b.classList.toggle('active', Number(b.dataset.pad) === this.pad));
    this.take.textContent = mine ? 'Release' : this.waiting ? 'Asking…' : 'Take control';
    this.take.classList.toggle('primary', !mine);
    this.take.classList.toggle('danger', mine);
    this.take.disabled = !mine && (this.waiting || !this.active || this.locked || (owner !== 0 && owner !== this.id));
    this.svg.classList.toggle('live', mine);

    const robotPad = this.robot && this.robot[this.pad - 1];
    let status;
    if (mine) status = `You are driving gamepad ${this.pad}. The robot also still takes the Driver Station's gamepad ${this.pad}.`;
    else if (owner) status = `Another browser is driving gamepad ${this.pad}. Showing what the robot gets.`;
    else if (this.locked) status = 'Locked: view only. Unlock with the PIN to drive.';
    else if (!this.active) status = 'No OpMode running. Init one (and Start it) to drive.';
    else status = `Showing gamepad ${this.pad} as the robot sees it${robotPad && robotPad.station ? ' (Driver Station)' : ''}. Take control to drive it from here.`;
    const statusElement = document.getElementById('pad-status');
    statusElement.textContent = status;
    statusElement.classList.toggle('driving', mine);

    document.getElementById('pad-device').innerHTML = device
      ? `<b>Controller plugged in:</b> ${escapeHtml(device.id.replace(/\s*\(.*$/, ''))}. It drives when you take control.`
      : 'No controller plugged in: use the picture (drag the sticks, click the buttons; right-click holds one). '
        + 'Plug one in and press a button on it to use it.';
    document.getElementById('pad-legend').innerHTML = `<div class="muted">Field Centric (Java), gamepad ${this.pad}</div>`
      + LEGEND[this.pad].map(([k, v]) => `<div><b>${k}</b> ${v}</div>`).join('');

    this.handle.classList.toggle('detected', !!device);
    this.handle.classList.toggle('driving', mine);
    document.getElementById('pad-handle-note').textContent = mine ? `driving gamepad ${this.pad}`
      : device ? 'controller detected' : '';
    this.pill.hidden = !mine;
    this.pill.textContent = `🎮 Driving gamepad ${this.pad} · Release`;
    this.render();
  }
}

