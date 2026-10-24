// Hardware map editor: the robot's configuration XML as a form instead of XML.
import { escapeHtml, getFile, putFile, toast } from './api.js';

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

function kindOf(tag) {
  for (const [kind, types] of Object.entries(TYPES)) if (types.includes(tag)) return kind;
  if (/Motor$/i.test(tag)) return 'motor';
  if (/ContinuousRotation/i.test(tag)) return 'crservo';
  if (/Servo$/i.test(tag)) return 'servo';
  return 'i2c';
}

export class HardwareEditor {
  constructor(holder) {
    this.holder = holder;
    this.path = null;
    this.hash = null;
    this.doc = null;
  }

  async load(path) {
    this.path = path;
    document.getElementById('hw-name').textContent = path || '';
    if (!path) {
      this.holder.innerHTML = '<p class="muted">No hardware map on the robot.</p>';
      return;
    }
    try {
      const file = await getFile(path);
      this.hash = file.hash;
      this.doc = new DOMParser().parseFromString(file.text, 'application/xml');
      if (this.doc.querySelector('parsererror')) throw new Error('the XML is broken');
      this.render();
    } catch (error) {
      this.holder.innerHTML = `<p class="warning">Could not read ${escapeHtml(path)}: ${escapeHtml(error.message)}</p>`;
    }
  }

  modules() {
    return [...this.doc.getElementsByTagName('LynxModule')];
  }

  devices(module) {
    return [...module.children];
  }

  render() {
    const parts = [];
    for (const [m, module] of this.modules().entries()) {
      parts.push(`<div class="hub-title">${escapeHtml(module.getAttribute('name'))}
        <span class="muted">address ${escapeHtml(module.getAttribute('port'))}</span></div>
        <table class="devices"><tr><th>Kind</th><th>Type</th><th>Name</th><th>Port</th><th>I2C bus</th><th></th></tr>`);
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
    parts.push(`<ul class="hw-problems" id="hw-problems"></ul>
      <div class="hw-actions"><button class="btn primary" id="hw-save">Save to robot</button>
      <button class="btn" id="hw-revert">Revert</button>
      <span class="muted">Restart the Robot Controller app (or re-INIT in the simulator) to use a saved map.</span></div>`);
    this.holder.innerHTML = parts.join('');
    this.holder.querySelectorAll('tr[data-m]').forEach((row) => {
      const device = this.devices(this.modules()[row.dataset.m])[row.dataset.d];
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
    this.holder.querySelectorAll('[data-add]').forEach((button) => button.addEventListener('click', () => {
      const kind = button.dataset.add;
      const element = this.doc.createElement(TYPES[kind][0]);
      element.setAttribute('name', 'newDevice');
      element.setAttribute('port', '0');
      if (kind === 'i2c') element.setAttribute('bus', '1');
      this.modules()[button.dataset.m].appendChild(element);
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
    this.holder.querySelectorAll('tr[data-m]').forEach((row) => row.classList.toggle('bad', bad.has(`${row.dataset.m}:${row.dataset.d}`)));
    document.getElementById('hw-problems').innerHTML = problems.map((p) => `<li>${escapeHtml(p)}</li>`).join('');
    return problems;
  }

  async save() {
    const problems = this.check();
    if (problems.length && !confirm(`${problems.length} problem(s) will stop the robot at INIT:\n\n${problems.join('\n')}\n\nSave anyway?`)) return;
    const text = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n${pretty(this.doc.documentElement, '')}\n`;
    try {
      const result = await putFile(this.path, text, this.hash);
      this.hash = result.hash;
      toast(`Saved ${this.path} on the robot`);
    } catch (error) {
      toast(error.message, true);
    }
  }
}

/** The same layout the Robot Controller writes: one element per line, 4-space indents. */
function pretty(element, indent) {
  const attributes = [...element.attributes].map((a) => ` ${a.name}="${escapeHtml(a.value)}"`).join('');
  const children = [...element.children];
  if (!children.length) return `${indent}<${element.tagName}${attributes} />`;
  return `${indent}<${element.tagName}${attributes}>\n${children.map((c) => pretty(c, `${indent}    `)).join('\n')}\n${indent}</${element.tagName}>`;
}
