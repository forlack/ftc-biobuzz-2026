// Hardware map editor: the robot's configuration XMLs as a form instead of XML. Several maps
// can live on the robot; the Robot Controller uses the one activated on the Driver Station.
import { api, escapeHtml, getFile, putFile, toast } from './api.js';

// Names the robot code asks hardwareMap for. A missing or misspelled one crashes INIT, so the
// form checks them before saving. Keep in sync with the Java code.
const REQUIRED = {
  motor: ['frontLeft', 'frontRight', 'backLeft', 'backRight', 'intake', 'shooterPollen', 'shooterNectar'],
  crservo: ['transportPollen', 'transportNectar', 'triggerPollen', 'triggerNectar'],
  servo: ['stinger'],
  i2c: ['pinpoint'],
};

const TYPES = {
  motor: ['goBILDA5202SeriesMotor', 'RevRobotics20HDHexMotor', 'RevRobotics40HDHexMotor',
    'RevRoboticsUltraPlanetaryHdHexMotor', 'RevRoboticsCoreHexMotor', 'Motor'],
  servo: ['Servo'],
  crservo: ['ContinuousRotationServo'],
  i2c: ['goBILDAPinpoint', 'ControlHubImuBHI260AP', 'RevColorSensorV3', 'REV_VL53L0X_RANGE_SENSOR'],
};
const PORTS = { motor: 4, servo: 6, crservo: 6, i2c: 8 };
const CONTROL_HUB = '173';   // the Control Hub's own address, the same on every Control Hub

/** A new map as the Robot Controller writes it: the Control Hub (with its IMU), maybe an Expansion Hub. */
function blankMap(expansionAddress) {
  const expansion = expansionAddress ? `        <LynxModule name="Expansion Hub ${expansionAddress}" port="${expansionAddress}" />\n` : '';
  return `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<Robot type="FirstInspires-FTC">
    <LynxUsbDevice name="Control Hub Portal" serialNumber="(embedded)" parentModuleAddress="${CONTROL_HUB}">
${expansion}        <LynxModule name="Control Hub" port="${CONTROL_HUB}">
            <ControlHubImuBHI260AP name="imu" port="0" bus="0" />
        </LynxModule>
    </LynxUsbDevice>
</Robot>
`;
}

function kindOf(tag) {
  for (const [kind, types] of Object.entries(TYPES)) if (types.includes(tag)) return kind;
  if (/Motor$/i.test(tag)) return 'motor';
  if (/ContinuousRotation/i.test(tag)) return 'crservo';
  if (/Servo$/i.test(tag)) return 'servo';
  return 'i2c';
}

export class HardwareEditor {
  constructor(holder, onFilesChanged) {
    this.holder = holder;
    this.onFilesChanged = onFilesChanged;
    this.path = null;
    this.hash = null;
    this.doc = null;
    this.maps = [];       // every *.xml on the robot
    this.active = '';     // the one the Robot Controller uses
    holder.innerHTML = '<div class="hw-maps" data-top></div><div class="hw-form" data-form hidden></div><div data-body></div>';
    this.top = holder.querySelector('[data-top]');
    this.form = holder.querySelector('[data-form]');
    this.body = holder.querySelector('[data-body]');
  }

  /** The maps on the robot and the active one; called whenever the file list is refreshed. */
  setMaps(maps, active) {
    this.maps = maps;
    this.active = active || '';
    document.getElementById('hw-name').textContent = this.active ? `robot uses ${this.active}` : '';
    this.renderTop();
  }

  async load(path) {
    this.path = path || null;
    this.doc = null;
    this.renderTop();
    if (!path) {
      this.body.innerHTML = `<p class="muted">No hardware map on the robot yet. Press <b>New map</b> to start one,
        or make one on the Driver Station (Configure Robot → New → Scan).</p>`;
      return;
    }
    try {
      const file = await getFile(path);
      this.hash = file.hash;
      this.doc = new DOMParser().parseFromString(file.text, 'application/xml');
      if (this.doc.querySelector('parsererror')) throw new Error('the XML is broken');
      this.render();
    } catch (error) {
      this.body.innerHTML = `<p class="warning">Could not read ${escapeHtml(path)}: ${escapeHtml(error.message)}</p>`;
    }
  }

  // ---- Which map: pick, new, copy, use ------------------------------------------------

  renderTop() {
    const options = this.maps.map((m) => `<option value="${escapeHtml(m)}" ${m === this.path ? 'selected' : ''}>${escapeHtml(m.replace(/\.xml$/, ''))}${m === this.active ? ' (in use)' : ''}</option>`);
    this.top.innerHTML = `${this.maps.length ? `<select id="hw-map" aria-label="Hardware map">${options.join('')}</select>` : ''}
      ${this.path && this.path !== this.active ? '<button class="btn small primary" id="hw-use">Use this map</button>' : ''}
      <button class="btn small" id="hw-new">New map</button>
      ${this.doc ? '<button class="btn small" id="hw-copy">Save as copy</button>' : ''}
      ${this.path && this.path !== this.active ? `<div class="hw-note">Not in use: the robot uses ${escapeHtml(this.active || 'no map')}. You can still edit this one.</div>` : ''}`;
    const select = this.top.querySelector('#hw-map');
    if (select) select.addEventListener('change', () => this.load(select.value));
    this.top.querySelector('#hw-new').addEventListener('click', () => this.showForm('new'));
    this.top.querySelector('#hw-copy')?.addEventListener('click', () => this.showForm('copy'));
    this.top.querySelector('#hw-use')?.addEventListener('click', () => this.use());
  }

  /** New map (with or without an Expansion Hub) or a copy of the open one. */
  showForm(kind) {
    this.form.hidden = false;
    this.form.innerHTML = `<b>${kind === 'new' ? 'New hardware map' : `Copy of ${escapeHtml(this.path)}`}</b>
      <label>Name <input id="hw-new-name" placeholder="CompBot" value="${kind === 'copy' ? escapeHtml(this.path.replace(/\.xml$/, '')) + '-copy' : ''}"></label>
      ${kind === 'new' ? `<label><input type="checkbox" id="hw-new-expansion" checked> This robot has an Expansion Hub</label>
      <label id="hw-new-address-row">at address <input id="hw-new-address" class="port" type="number" min="1" max="254" value="2"></label>
      <div class="muted">The Control Hub is always there. Out of the box an Expansion Hub is at address 2.</div>` : ''}
      <div class="hw-actions"><button class="btn primary" id="hw-new-create">${kind === 'new' ? 'Create' : 'Save copy'}</button>
        <button class="btn" id="hw-new-cancel">Cancel</button></div>`;
    const expansion = this.form.querySelector('#hw-new-expansion');
    if (expansion) expansion.addEventListener('change', () => { this.form.querySelector('#hw-new-address-row').hidden = !expansion.checked; });
    this.form.querySelector('#hw-new-cancel').addEventListener('click', () => { this.form.hidden = true; });
    this.form.querySelector('#hw-new-create').addEventListener('click', () => this.create(kind));
    this.form.querySelector('#hw-new-name').focus();
  }

  async create(kind) {
    const name = this.form.querySelector('#hw-new-name').value.trim();
    if (!/^[A-Za-z0-9][A-Za-z0-9 _-]*$/.test(name)) { toast('Use letters, digits, spaces, - or _ for the name', true); return; }
    const path = `${name}.xml`;
    if (this.maps.includes(path)) { toast(`${path} already exists`, true); return; }
    let text;
    if (kind === 'new') {
      const address = this.form.querySelector('#hw-new-expansion').checked ? this.form.querySelector('#hw-new-address').value.trim() : null;
      if (address !== null && !validAddress(address)) { toast('The Expansion Hub address must be 1 to 254, and not 173', true); return; }
      text = blankMap(address);
    } else {
      text = this.serialize();
    }
    try {
      await putFile(path, text, 'new');
      toast(`Made ${path} on the robot`);
      this.form.hidden = true;
      this.maps = [...this.maps, path].sort();
      await this.load(path);
      if (this.onFilesChanged) this.onFilesChanged();
    } catch (error) {
      toast(error.message, true);
    }
  }

  async use() {
    try {
      const result = await api('/api/hardware/active', { method: 'POST', body: { path: this.path } });
      if (result.switched) {
        toast(`The robot now uses ${this.path}`);
        this.setMaps(this.maps, result.active);
      } else {
        this.form.hidden = false;
        this.form.innerHTML = `<b>One more step</b><div>${escapeHtml(result.message)}</div>
          <div class="muted">This page shows the switch by itself once it is done.</div>
          <div class="hw-actions"><button class="btn" id="hw-new-cancel">OK</button></div>`;
        this.form.querySelector('#hw-new-cancel').addEventListener('click', () => { this.form.hidden = true; });
      }
    } catch (error) {
      toast(error.message, true);
    }
  }

  // ---- Hubs and devices ------------------------------------------------------------------

  /** The Control Hub first, then the Expansion Hub (the file order does not matter to the robot). */
  modules() {
    const all = [...this.doc.getElementsByTagName('LynxModule')];
    return all.sort((a, b) => (b.getAttribute('port') === CONTROL_HUB) - (a.getAttribute('port') === CONTROL_HUB));
  }

  /** USB devices outside the hubs, such as webcams. */
  usbDevices() {
    return [...this.doc.documentElement.children].filter((e) => e.tagName !== 'LynxUsbDevice');
  }

  addExpansionHub() {
    const address = prompt('Expansion Hub address (out of the box it is 2):', '2');
    if (address === null) return;
    if (!validAddress(address.trim())) { toast('The address must be 1 to 254, and not 173', true); return; }
    const portal = this.doc.getElementsByTagName('LynxUsbDevice')[0];
    const hub = this.doc.createElement('LynxModule');
    hub.setAttribute('name', `Expansion Hub ${address.trim()}`);
    hub.setAttribute('port', address.trim());
    portal.insertBefore(hub, portal.firstElementChild);   // where the Robot Controller puts it
    this.render();
  }

  devices(module) {
    return [...module.children];
  }

  render() {
    this.renderTop();
    const parts = [];
    const modules = this.modules();
    for (const [m, module] of modules.entries()) {
      const isControlHub = module.getAttribute('port') === CONTROL_HUB;
      parts.push(`<div class="hub-title">${escapeHtml(module.getAttribute('name'))}
        <span class="muted">address ${escapeHtml(module.getAttribute('port'))}</span>
        ${isControlHub ? '' : `<button class="btn small danger" data-remove-hub="${m}">Remove hub</button>`}</div>
        ${this.devices(module).length ? '<table class="devices"><tr><th>Kind</th><th>Type</th><th>Name</th><th>Port</th><th>I2C bus</th><th></th></tr>'
          : '<div class="muted">No devices yet.</div><table class="devices">'}`);
      for (const [d, device] of this.devices(module).entries()) {
        const kind = kindOf(device.tagName);
        const types = [...new Set([device.tagName, ...TYPES[kind]])];
        parts.push(`<tr data-m="${m}" data-d="${d}">
          <td class="muted">${kind}</td>
          <td><select data-field="type">${types.map((t) => `<option ${t === device.tagName ? 'selected' : ''}>${escapeHtml(t)}</option>`).join('')}</select></td>
          <td><input data-field="name" value="${escapeHtml(device.getAttribute('name') || '')}"></td>
          <td><input class="port" data-field="port" type="number" min="0" value="${escapeHtml(device.getAttribute('port') || '0')}"></td>
          <td>${kind === 'i2c' ? `<input class="port" data-field="bus" type="number" min="0" max="3" value="${escapeHtml(device.getAttribute('bus') || '0')}">` : ''}</td>
          <td><button class="btn small danger" data-remove>Remove</button></td></tr>`);
      }
      parts.push(`</table><div class="hw-actions" style="margin-top:6px">
        ${['motor', 'servo', 'crservo', 'i2c'].map((k) => `<button class="btn small" data-add="${k}" data-m="${m}">+ ${k === 'crservo' ? 'CR servo' : k === 'i2c' ? 'I2C device' : k}</button>`).join('')}
      </div>`);
    }
    if (this.doc.getElementsByTagName('LynxUsbDevice').length && !modules.some((h) => h.getAttribute('port') !== CONTROL_HUB)) {
      parts.push('<div class="hw-actions"><button class="btn small" id="hw-add-hub">+ Expansion Hub</button></div>');
    }
    const usb = this.usbDevices();
    parts.push(`<div class="hub-title">USB devices</div>
      ${usb.length ? `<table class="devices"><tr><th>Type</th><th>Name</th><th>Serial number</th></tr>
        ${usb.map((device, u) => `<tr data-u="${u}"><td class="muted">${escapeHtml(device.tagName)}</td>
          <td><input data-field="name" value="${escapeHtml(device.getAttribute('name') || '')}"></td>
          <td class="muted">${escapeHtml(device.getAttribute('serialNumber') || '')}</td></tr>`).join('')}</table>` : ''}
      <div class="muted">To add a webcam: plug it in, then on the Driver Station Configure Robot → this map → Scan, and Save.
        It then shows here, and you can rename it.</div>`);
    parts.push(`<ul class="hw-problems" id="hw-problems"></ul>
      <div class="hw-actions"><button class="btn primary" id="hw-save">Save to robot</button>
      <button class="btn" id="hw-revert">Revert</button>
      <span class="muted">Restart the Robot Controller app (or re-INIT in the simulator) to use a saved map.</span></div>`);
    this.body.innerHTML = parts.join('');
    this.body.querySelector('#hw-add-hub')?.addEventListener('click', () => this.addExpansionHub());
    this.body.querySelectorAll('[data-remove-hub]').forEach((button) => button.addEventListener('click', () => {
      const hub = modules[button.dataset.removeHub];
      const count = this.devices(hub).length;
      if (!confirm(`Remove ${hub.getAttribute('name')}${count ? ` and its ${count} device(s)` : ''} from this map?`)) return;
      hub.remove();
      this.render();
    }));
    this.body.querySelectorAll('tr[data-u]').forEach((row) => {
      const device = usb[row.dataset.u];
      row.querySelector('input').addEventListener('change', (event) => { device.setAttribute('name', event.target.value.trim()); this.check(); });
    });
    this.body.querySelectorAll('tr[data-m]').forEach((row) => {
      const device = this.devices(modules[row.dataset.m])[row.dataset.d];
      row.querySelectorAll('[data-field]').forEach((input) => input.addEventListener('change', () => {
        const field = input.dataset.field;
        if (field === 'type') {
          const replacement = this.doc.createElement(input.value);
          for (const attribute of [...device.attributes]) replacement.setAttribute(attribute.name, attribute.value);
          device.replaceWith(replacement);
          this.render();
          return;
        }
        device.setAttribute(field, input.value.trim());
        this.check();
      }));
      row.querySelector('[data-remove]').addEventListener('click', () => { device.remove(); this.render(); });
    });
    this.body.querySelectorAll('[data-add]').forEach((button) => button.addEventListener('click', () => {
      const kind = button.dataset.add;
      const element = this.doc.createElement(TYPES[kind][0]);
      element.setAttribute('name', 'newDevice');
      element.setAttribute('port', '0');
      if (kind === 'i2c') element.setAttribute('bus', '1');
      modules[button.dataset.m].appendChild(element);
      this.render();
    }));
    document.getElementById('hw-save').addEventListener('click', () => this.save());
    document.getElementById('hw-revert').addEventListener('click', () => this.load(this.path));
    this.check();
  }

  /** Problems that would make the robot fail at INIT, or the Robot Controller refuse the map. */
  problems() {
    const problems = [];
    const names = new Map();
    const bad = new Set();
    const addresses = new Set();
    for (const module of this.modules()) {
      const address = module.getAttribute('port');
      if (address !== CONTROL_HUB && !validAddress(address)) problems.push(`${module.getAttribute('name')}: address ${address} must be 1 to 254, and not 173`);
      if (addresses.has(address)) problems.push(`Two hubs use address ${address}`);
      addresses.add(address);
    }
    for (const device of this.usbDevices()) {
      const name = device.getAttribute('name') || '';
      if (!name.trim()) problems.push(`A ${device.tagName} has no name`);
      else if (names.has(name)) problems.push(`Two devices are called "${name}"`);
      names.set(name, `usb:${name}`);
    }
    for (const [m, module] of this.modules().entries()) {
      const used = new Map();
      for (const [d, device] of this.devices(module).entries()) {
        const kind = kindOf(device.tagName);
        const name = device.getAttribute('name') || '';
        const port = Number(device.getAttribute('port'));
        const id = `${m}:${d}`;
        if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(name)) { problems.push(`"${name}" is not a valid name (letters, digits, _)`); bad.add(id); }
        if (names.has(name)) { problems.push(`Two devices are called "${name}"`); bad.add(id); bad.add(names.get(name)); }
        names.set(name, id);
        if (!Number.isInteger(port) || port < 0 || port >= PORTS[kind]) { problems.push(`${name}: port ${device.getAttribute('port')} does not exist for a ${kind}`); bad.add(id); }
        const slot = kind === 'i2c' ? `i2c ${device.getAttribute('bus')}/${port}` : `${kind === 'crservo' ? 'servo' : kind} ${port}`;
        if (used.has(slot)) { problems.push(`${name} and ${used.get(slot)} are both on ${slot} of ${module.getAttribute('name')}`); bad.add(id); }
        used.set(slot, name);
      }
    }
    const present = new Map();
    for (const module of this.modules()) for (const device of this.devices(module)) present.set(device.getAttribute('name'), kindOf(device.tagName));
    for (const [kind, list] of Object.entries(REQUIRED)) {
      for (const name of list) {
        if (!present.has(name)) problems.push(`The code needs a ${kind === 'crservo' ? 'CR servo' : kind} called "${name}"`);
        else if (present.get(name) !== kind) problems.push(`"${name}" must be a ${kind === 'crservo' ? 'CR servo' : kind}, not a ${present.get(name)}`);
      }
    }
    return { problems, bad };
  }

  check() {
    const { problems, bad } = this.problems();
    this.body.querySelectorAll('tr[data-m]').forEach((row) => row.classList.toggle('bad', bad.has(`${row.dataset.m}:${row.dataset.d}`)));
    document.getElementById('hw-problems').innerHTML = problems.map((p) => `<li>${escapeHtml(p)}</li>`).join('');
    return problems;
  }

  serialize() {
    return `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n${pretty(this.doc.documentElement, '')}\n`;
  }

  async save() {
    const problems = this.check();
    if (problems.length && !confirm(`${problems.length} problem(s) will stop the robot at INIT:\n\n${problems.join('\n')}\n\nSave anyway?`)) return;
    try {
      const result = await putFile(this.path, this.serialize(), this.hash);
      this.hash = result.hash;
      toast(`Saved ${this.path} on the robot`);
    } catch (error) {
      toast(error.message, true);
    }
  }
}

function validAddress(text) {
  const n = Number(text);
  return /^\d+$/.test(String(text)) && n >= 1 && n <= 254 && String(n) !== CONTROL_HUB;
}

/** The same layout the Robot Controller writes: one element per line, 4-space indents. */
function pretty(element, indent) {
  const attributes = [...element.attributes].map((a) => ` ${a.name}="${escapeHtml(a.value)}"`).join('');
  const children = [...element.children];
  if (!children.length) return `${indent}<${element.tagName}${attributes} />`;
  return `${indent}<${element.tagName}${attributes}>\n${children.map((c) => pretty(c, `${indent}    `)).join('\n')}\n${indent}</${element.tagName}>`;
}
