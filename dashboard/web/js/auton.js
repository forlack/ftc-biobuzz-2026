// Auton tab: our own auton editor. It edits autos/*.json (version 3, lib/AutoRoute.java)
// directly: the start pose, then the steps in order, drives and functions alike. A drive starts
// where the previous one ended, so there is nothing to line up.
//
// Field: Pedro coordinates, 144 x 144 in, x right, y up, headings in degrees counter-clockwise.
import { api, escapeHtml, getFile, putFile, toast } from './api.js';

const FIELD = 144;
const TOP_SPEED = 68.5;            // in/s, Foresight maxAchievableForwardVelocity (for the estimate)
const ACCEL = 50, DECEL = 40;      // in/s², rough, for the time estimate only
const COLORS = ['#f5b301', '#58a6ff', '#3fb950', '#bc8cff', '#ff7b72', '#39c5cf', '#ffa657', '#d2a8ff'];
const HEADINGS = [
  ['travel', 'Face where it is going'],
  ['backwards', 'Drive backwards'],
  ['keep', 'Keep facing'],
  ['turn', 'Turn to…'],
  ['face', 'Face a point'],
];

// ---- Geometry ------------------------------------------------------------------------------

const lerp = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];

/** A point on the Bézier curve through control points pts (any degree). */
function bezier(pts, t) {
  let p = pts;
  while (p.length > 1) p = p.slice(1).map((q, i) => lerp(p[i], q, t));
  return p[0];
}

/** Splits a Bézier curve at t into two curves with the same shape. */
function splitBezier(pts, t) {
  const left = [], right = [];
  let p = pts;
  while (p.length) {
    left.push(p[0]);
    right.unshift(p[p.length - 1]);
    p = p.slice(1).map((q, i) => lerp(p[i], q, t));
  }
  return [left, right];
}

const degrees = (radians) => (radians * 180) / Math.PI;
const wrap = (deg) => ((deg % 360) + 360) % 360;
/** The shorter way from a to b, in [-180, 180): exactly half a turn goes clockwise (as the robot). */
const shortest = (a, b) => { const c = b - a; return c - 360 * Math.floor((c + 180) / 360); };
const round = (v) => Math.round(v * 100) / 100;

/**
 * Where everything is: for each step, a drive's start pose, segments (absolute control points),
 * samples along it, its length and end pose. Mirrors lib/AutoRoute.java's rules.
 */
function layout(route) {
  let pose = { x: route.start.x, y: route.start.y, h: route.start.heading };
  return route.steps.map((step) => {
    if (!step.drive) return { start: pose, end: pose };
    const drive = step.drive;
    const start = pose;
    let from = [start.x, start.y];
    const segments = drive.segments.map((segment) => {
      const pts = [from, ...(segment.via || []), segment.to];
      const samples = [];
      for (let i = 0; i <= 40; i++) samples.push(bezier(pts, i / 40));
      let length = 0;
      for (let i = 1; i < samples.length; i++) length += Math.hypot(samples[i][0] - samples[i - 1][0], samples[i][1] - samples[i - 1][1]);
      from = segment.to;
      return { pts, samples, length };
    });
    const length = segments.reduce((sum, s) => sum + s.length, 0);
    const last = segments[segments.length - 1].pts;
    const end = last[last.length - 1];
    const heading = drive.heading || { type: 'travel' };
    let h;
    if (heading.type === 'keep') h = start.h;
    else if (heading.type === 'turn') h = start.h + shortest(start.h, heading.degrees);
    else if (heading.type === 'face') h = degrees(Math.atan2(heading.y - end[1], heading.x - end[0]));
    else {
      const before = last[last.length - 2];
      h = degrees(Math.atan2(end[1] - before[1], end[0] - before[0])) + (heading.reverse ? 180 : 0);
    }
    pose = { x: end[0], y: end[1], h };
    return { start, end: pose, segments, length };
  });
}

/** The point at fraction f (0 to 1) of a laid-out drive's whole length. */
function pointAlong(info, f) {
  let left = f * info.length;
  for (const segment of info.segments) {
    for (let i = 1; i < segment.samples.length; i++) {
      const a = segment.samples[i - 1], b = segment.samples[i];
      const d = Math.hypot(b[0] - a[0], b[1] - a[1]);
      if (left <= d || (segment === info.segments.at(-1) && i === segment.samples.length - 1)) return lerp(a, b, d ? Math.min(1, left / d) : 0);
      left -= d;
    }
  }
  return info.segments.at(-1).samples.at(-1);
}

/** Seconds a drive takes, from a simple speed-up / cruise / slow-down profile. */
function driveSeconds(length, percent) {
  const v = (TOP_SPEED * (percent || 100)) / 100;
  if (length <= 0) return 0;
  const ramps = (v * v) / (2 * ACCEL) + (v * v) / (2 * DECEL);
  if (length >= ramps) return length / v + v / (2 * ACCEL) + v / (2 * DECEL);
  const peak = Math.sqrt((2 * length * ACCEL * DECEL) / (ACCEL + DECEL));
  return peak / ACCEL + peak / DECEL;
}

/** Seconds a function step takes (the robot's own steps; instant functions take none). */
function functionSeconds(step) {
  const p = step.params || {};
  switch (step.action) {
    case 'wait': return p.seconds ?? 0.5;
    case 'shoot': return (p.seconds ?? 3.5) + 0.6;
    case 'waitForFlywheels': return Math.min(p.timeout ?? 2, 1);
    case 'turnTo': return 0.8;
    default: return 0;
  }
}

// ---- The tab ---------------------------------------------------------------------------------

export class AutonTab {
  constructor() {
    this.root = document.getElementById('tab-auton');
    this.canvas = document.getElementById('auton-field');
    this.panel = document.getElementById('auton-panel');
    this.image = new Image();
    this.image.src = 'fields/biobuzz.webp';
    this.image.onload = () => this.draw();
    this.actions = [];
    this.files = [];
    this.route = null;       // the auton being edited (version 3 JSON)
    this.path = null;        // autos/NAME.json it came from, or null for a new one
    this.hash = 'new';       // its hash when opened, so a save refuses someone else's change
    this.selected = -1;      // step index
    this.mode = null;        // null | 'add-drive' | 'pick-face'
    this.drag = null;
    this.hover = null;       // field point under the mouse
    this.undoStack = [];
    this.redoStack = [];
    this.saved = '';         // JSON as last opened or saved, to show unsaved changes

    this.bindField();
    new ResizeObserver(() => this.resize()).observe(this.canvas.parentElement);
    document.addEventListener('keydown', (event) => this.key(event));
  }

  /** Called when the tab is shown. */
  async refresh() {
    try {
      if (!this.actions.length) this.actions = await api('/api/actions');
      this.files = (await api('/api/files')).map((f) => f.path).filter((p) => /^autos\/[^/]+\.json$/.test(p)).sort();
    } catch (error) {
      toast(`Could not reach the robot: ${error.message}`, true);
    }
    if (!this.route) {
      let last = null;
      try { last = localStorage.getItem('biobuzz.auton'); } catch { /* private window */ }
      const first = this.files.includes(last) ? last : this.files[0];
      if (first) await this.open(first); else this.newAuton();
    }
    this.render();
  }

  // ---- Files ---------------------------------------------------------------------------------

  async open(path) {
    if (this.dirty() && !window.confirm('Discard the changes to this auton?')) { this.render(); return; }
    try {
      const { text, hash } = await getFile(path);
      const route = JSON.parse(text);
      if (route.version !== 3) throw new Error(`${path} is version ${route.version}: convert it with tools/autos_v2_to_v3.py`);
      this.route = route;
      this.path = path;
      this.hash = hash;
      this.selected = -1;
      this.undoStack = [];
      this.redoStack = [];
      this.saved = JSON.stringify(route);
      try { localStorage.setItem('biobuzz.auton', path); } catch { /* private window */ }
    } catch (error) {
      toast(error.message, true);
    }
    this.render();
  }

  newAuton() {
    const start = this.route ? { ...this.route.start } : { x: 56, y: 9, heading: 90 };
    this.route = { version: 3, name: 'new auton', start, steps: [] };
    this.path = null;
    this.hash = 'new';
    this.selected = -1;
    this.undoStack = [];
    this.redoStack = [];
    this.saved = '';
    this.render();
  }

  dirty() {
    return this.route && JSON.stringify(this.route) !== this.saved;
  }

  async save(asNew = false) {
    let path = this.path;
    if (asNew || !path) {
      const name = window.prompt('Save as (letters, digits, - and _):', asNew ? '' : (this.route.name || '').replace(/[^A-Za-z0-9_-]+/g, '-'));
      if (!name) return;
      if (!/^[A-Za-z0-9_-]+$/.test(name)) { toast('Use only letters, digits, - and _', true); return; }
      path = `autos/${name}.json`;
      if (this.files.includes(path) && !window.confirm(`${path} already exists. Replace it?`)) return;
      if (asNew) this.route.name = name;
    }
    const base = path === this.path ? this.hash : (this.files.includes(path) ? '*' : 'new');
    try {
      const result = await putFile(path, `${JSON.stringify(this.route, null, 2)}\n`, base);
      this.path = path;
      this.hash = result.hash;
      this.saved = JSON.stringify(this.route);
      if (!this.files.includes(path)) this.files = [...this.files, path].sort();
      try { localStorage.setItem('biobuzz.auton', path); } catch { /* private window */ }
      toast(`Saved ${path} on the robot`);
    } catch (error) {
      toast(error.message, true);   // the robot checks the whole auton: its message says what is wrong
    }
    this.render();
  }

  // ---- Changes, undo ----------------------------------------------------------------------------

  /** Records the auton before a change, for undo. */
  remember() {
    this.undoStack.push(JSON.stringify(this.route));
    if (this.undoStack.length > 100) this.undoStack.shift();
    this.redoStack = [];
  }

  change(fn) {
    this.remember();
    fn(this.route);
    this.render();
  }

  undo() {
    if (!this.undoStack.length) return;
    this.redoStack.push(JSON.stringify(this.route));
    this.route = JSON.parse(this.undoStack.pop());
    if (this.selected >= this.route.steps.length) this.selected = this.route.steps.length - 1;
    this.render();
  }

  redo() {
    if (!this.redoStack.length) return;
    this.undoStack.push(JSON.stringify(this.route));
    this.route = JSON.parse(this.redoStack.pop());
    this.render();
  }

  key(event) {
    if (!this.root.classList.contains('active')) return;
    if (event.target.closest && event.target.closest('input, select, textarea')) return;
    const mod = event.ctrlKey || event.metaKey;
    if (mod && event.key.toLowerCase() === 'z') { event.preventDefault(); if (event.shiftKey) this.redo(); else this.undo(); }
    else if (mod && event.key.toLowerCase() === 'y') { event.preventDefault(); this.redo(); }
    else if (event.key === 'Escape' && this.mode) { this.mode = null; this.render(); }
  }

  // ---- Steps ---------------------------------------------------------------------------------------

  insertIndex() {
    return this.selected >= 0 ? this.selected + 1 : this.route.steps.length;
  }

  driveCount() {
    return this.route.steps.filter((s) => s.drive).length;
  }

  addDriveTo(point) {
    const used = new Set(this.route.steps.filter((s) => s.drive).map((s) => s.drive.name));
    let n = this.driveCount() + 1;
    while (used.has(`path${n}`)) n++;
    const at = this.insertIndex();
    this.change((route) => route.steps.splice(at, 0, { drive: { name: `path${n}`, segments: [{ to: point }], heading: { type: 'keep' } } }));
    this.selected = at;
    this.mode = null;
    this.render();
  }

  addFunction(name) {
    const def = this.actions.find((a) => a.name === name);
    if (!def) return;
    const params = Object.fromEntries(def.params.map((p) => [p.name, p.default]));
    const at = this.insertIndex();
    this.change((route) => route.steps.splice(at, 0, { action: name, params }));
    this.selected = at;
    this.render();
  }

  move(index, by) {
    const to = index + by;
    if (to < 0 || to >= this.route.steps.length) return;
    this.change((route) => { const [step] = route.steps.splice(index, 1); route.steps.splice(to, 0, step); });
    this.selected = to;
    this.render();
  }

  remove(index) {
    this.change((route) => route.steps.splice(index, 1));
    this.selected = Math.min(index, this.route.steps.length - 1);
    this.render();
  }

  /** Splits the selected drive at a point on it: a new waypoint, same shape. */
  splitAt(stepIndex, segmentIndex, t) {
    this.change((route) => {
      const info = layout(route)[stepIndex];
      const [left, right] = splitBezier(info.segments[segmentIndex].pts, t);
      const point = left[left.length - 1].map(round);
      route.steps[stepIndex].drive.segments.splice(segmentIndex, 1,
        { ...(left.length > 2 ? { via: left.slice(1, -1).map((p) => p.map(round)) } : {}), to: point },
        { ...(right.length > 2 ? { via: right.slice(1, -1).map((p) => p.map(round)) } : {}), to: route.steps[stepIndex].drive.segments[segmentIndex].to });
    });
  }

  /** Removes a waypoint: the two segments around it become one, keeping both sides' bends. */
  removeWaypoint(stepIndex, segmentIndex) {
    this.change((route) => {
      const segments = route.steps[stepIndex].drive.segments;
      const a = segments[segmentIndex], b = segments[segmentIndex + 1];
      const via = [...(a.via || []), ...(b.via || [])];
      segments.splice(segmentIndex, 2, { ...(via.length ? { via } : {}), to: b.to });
    });
  }

  /** Adds a bend point to a segment: half way along, pushed sideways so it can be seen. */
  addBend(stepIndex, segmentIndex) {
    this.change((route) => {
      const info = layout(route)[stepIndex];
      const pts = info.segments[segmentIndex].pts;
      const a = pts[0], b = pts[pts.length - 1];
      const mid = bezier(pts, 0.5);
      const len = Math.hypot(b[0] - a[0], b[1] - a[1]) || 1;
      const side = [-(b[1] - a[1]) / len, (b[0] - a[0]) / len];
      const point = [round(mid[0] + side[0] * Math.min(12, len / 4)), round(mid[1] + side[1] * Math.min(12, len / 4))];
      const segment = route.steps[stepIndex].drive.segments[segmentIndex];
      segment.via = [...(segment.via || []), point];
    });
  }

  /**
   * Makes a waypoint smooth: the bend points on each side line up through it, so the robot
   * does not change direction suddenly there. A straight side gets a bend point first.
   */
  smooth(stepIndex, segmentIndex) {
    this.change((route) => {
      const segments = route.steps[stepIndex].drive.segments;
      const info = layout(route)[stepIndex];
      const a = segments[segmentIndex], b = segments[segmentIndex + 1];
      const p = a.to;
      const before = info.segments[segmentIndex].pts.at(-2);
      const after = info.segments[segmentIndex + 1].pts[1];
      // The direction through the waypoint: from the point before to the point after.
      let dx = after[0] - before[0], dy = after[1] - before[1];
      const d = Math.hypot(dx, dy) || 1;
      dx /= d; dy /= d;
      const lenA = Math.max(6, Math.hypot(p[0] - before[0], p[1] - before[1]) * (a.via?.length ? 1 : 0.4));
      const lenB = Math.max(6, Math.hypot(after[0] - p[0], after[1] - p[1]) * (b.via?.length ? 1 : 0.4));
      const inPoint = [round(p[0] - dx * lenA), round(p[1] - dy * lenA)];
      const outPoint = [round(p[0] + dx * lenB), round(p[1] + dy * lenB)];
      a.via = a.via?.length ? [...a.via.slice(0, -1), inPoint] : [inPoint];
      b.via = b.via?.length ? [outPoint, ...b.via.slice(1)] : [outPoint];
    });
  }

  // ---- Field -----------------------------------------------------------------------------------------

  resize() {
    const box = this.canvas.parentElement.getBoundingClientRect();
    const size = Math.max(200, Math.floor(Math.min(box.width, box.height)));
    const ratio = window.devicePixelRatio || 1;
    this.canvas.style.width = `${size}px`;
    this.canvas.style.height = `${size}px`;
    this.canvas.width = size * ratio;
    this.canvas.height = size * ratio;
    this.draw();
  }

  /** Field inches -> canvas pixels, and back. */
  px([x, y]) { const s = this.canvas.width / FIELD; return [x * s, this.canvas.height - y * s]; }

  fieldPoint(event) {
    const rect = this.canvas.getBoundingClientRect();
    const x = ((event.clientX - rect.left) / rect.width) * FIELD;
    const y = FIELD - ((event.clientY - rect.top) / rect.height) * FIELD;
    // Snap to half inches; Shift places freely (tenths).
    const step = event.shiftKey ? 0.1 : 0.5;
    const snap = (v) => Math.round(Math.min(FIELD, Math.max(0, v)) / step) * step;
    return [round(snap(x)), round(snap(y))];
  }

  /** Every draggable thing on the field, topmost first. */
  handles() {
    if (!this.route) return [];
    const list = [];
    const info = layout(this.route);
    const route = this.route;
    // The start pose: its body moves it, the dot ahead of it turns it.
    const ahead = 12;
    const sh = (route.start.heading * Math.PI) / 180;
    list.push({ kind: 'start-heading', at: [route.start.x + Math.cos(sh) * ahead, route.start.y + Math.sin(sh) * ahead] });
    const step = route.steps[this.selected];
    if (step && step.drive) {
      const drive = step.drive;
      if (drive.heading && drive.heading.type === 'face') list.push({ kind: 'face', at: [drive.heading.x, drive.heading.y] });
      drive.segments.forEach((segment, s) => {
        (segment.via || []).forEach((via, v) => list.push({ kind: 'via', step: this.selected, segment: s, index: v, at: via }));
        list.push({ kind: 'to', step: this.selected, segment: s, at: segment.to });
      });
      (drive.events || []).forEach((event, e) => list.push({ kind: 'event', step: this.selected, index: e, at: pointAlong(info[this.selected], event.at) }));
    }
    // Every drive's end point (its shared corner with the next drive), then the start.
    route.steps.forEach((s, i) => {
      if (s.drive && i !== this.selected) list.push({ kind: 'to', step: i, segment: s.drive.segments.length - 1, at: s.drive.segments.at(-1).to });
    });
    list.push({ kind: 'start', at: [route.start.x, route.start.y] });
    return list;
  }

  hit(event) {
    const rect = this.canvas.getBoundingClientRect();
    const scale = this.canvas.width / rect.width;
    const mouse = [(event.clientX - rect.left) * scale, (event.clientY - rect.top) * scale];
    const radius = 10 * (window.devicePixelRatio || 1);
    return this.handles().find((h) => { const [x, y] = this.px(h.at); return Math.hypot(x - mouse[0], y - mouse[1]) <= radius; });
  }

  /** The nearest point on a drive: [step, segment, t, distance in inches]. */
  nearestOnDrive(point, onlyStep = null) {
    let best = null;
    layout(this.route).forEach((info, i) => {
      if (!info.segments || (onlyStep !== null && i !== onlyStep)) return;
      info.segments.forEach((segment, s) => {
        for (let k = 0; k <= 200; k++) {
          const t = k / 200;
          const p = bezier(segment.pts, t);
          const d = Math.hypot(p[0] - point[0], p[1] - point[1]);
          if (!best || d < best[3]) best = [i, s, t, d];
        }
      });
    });
    return best;
  }

  bindField() {
    const canvas = this.canvas;
    canvas.addEventListener('contextmenu', (event) => {
      event.preventDefault();
      const handle = this.hit(event);
      if (!handle) return;
      const drive = handle.step !== undefined && this.route.steps[handle.step].drive;
      if (handle.kind === 'via') this.change(() => { drive.segments[handle.segment].via.splice(handle.index, 1); if (!drive.segments[handle.segment].via.length) delete drive.segments[handle.segment].via; });
      else if (handle.kind === 'to' && handle.segment < drive.segments.length - 1) this.removeWaypoint(handle.step, handle.segment);
      else if (handle.kind === 'event') this.change(() => drive.events.splice(handle.index, 1));
    });
    canvas.addEventListener('dblclick', (event) => {
      if (!this.route || this.mode) return;
      const point = this.fieldPoint(event);
      const near = this.nearestOnDrive(point, this.route.steps[this.selected]?.drive ? this.selected : null);
      if (near && near[3] < 4 && near[2] > 0.02 && near[2] < 0.98) {
        this.selected = near[0];
        this.splitAt(near[0], near[1], near[2]);
      }
    });
    canvas.addEventListener('pointerdown', (event) => {
      if (!this.route || event.button !== 0) return;
      const point = this.fieldPoint(event);
      if (this.mode === 'add-drive') { this.addDriveTo(point); return; }
      if (this.mode === 'pick-face') {
        this.change(() => { const d = this.route.steps[this.selected].drive; d.heading = { type: 'face', x: point[0], y: point[1] }; });
        this.mode = null;
        this.render();
        return;
      }
      const handle = this.hit(event);
      if (handle) {
        this.remember();
        this.drag = handle;
        canvas.setPointerCapture(event.pointerId);
        if (handle.step !== undefined && handle.kind !== 'event' && this.selected !== handle.step) { this.selected = handle.step; this.render(); }
        return;
      }
      // Clicking a drive selects it.
      const near = this.nearestOnDrive(point);
      if (near && near[3] < 4) { this.selected = near[0]; this.render(); }
    });
    canvas.addEventListener('pointermove', (event) => {
      if (!this.route) return;
      this.hover = this.fieldPoint(event);
      const handle = this.drag;
      if (!handle) {
        canvas.style.cursor = this.mode ? 'crosshair' : this.hit(event) ? 'grab' : 'default';
        if (this.mode) this.draw();
        return;
      }
      const point = this.hover;
      const route = this.route;
      if (handle.kind === 'start') { route.start.x = point[0]; route.start.y = point[1]; }
      else if (handle.kind === 'start-heading') {
        const h = degrees(Math.atan2(point[1] - route.start.y, point[0] - route.start.x));
        route.start.heading = Math.round(wrap(h) / (event.shiftKey ? 1 : 15)) * (event.shiftKey ? 1 : 15) % 360;
      } else if (handle.kind === 'face') { const d = route.steps[this.selected].drive; d.heading.x = point[0]; d.heading.y = point[1]; }
      else if (handle.kind === 'via') route.steps[handle.step].drive.segments[handle.segment].via[handle.index] = point;
      else if (handle.kind === 'to') route.steps[handle.step].drive.segments[handle.segment].to = point;
      else if (handle.kind === 'event') {
        const near = this.nearestOnDrive(point, handle.step);
        if (near) {
          const info = layout(route)[handle.step];
          let before = 0;
          for (let s = 0; s < near[1]; s++) before += info.segments[s].length;
          const segment = info.segments[near[1]];
          const along = before + segment.length * near[2];   // close enough for placing by hand
          route.steps[handle.step].drive.events[handle.index].at = Math.round((along / info.length) * 100) / 100;
        }
      }
      this.render();
    });
    const end = () => {
      if (!this.drag) return;
      // A drag that changed nothing is not an undo step.
      if (this.undoStack.at(-1) === JSON.stringify(this.route)) this.undoStack.pop();
      this.drag = null;
      this.render();
    };
    canvas.addEventListener('pointerup', end);
    canvas.addEventListener('pointercancel', end);
    canvas.addEventListener('pointerleave', () => { this.hover = null; if (this.mode) this.draw(); });
  }

  draw() {
    const canvas = this.canvas;
    const g = canvas.getContext('2d');
    const size = canvas.width;
    const s = size / FIELD;
    const k = window.devicePixelRatio || 1;
    g.clearRect(0, 0, size, size);
    if (this.image.complete && this.image.naturalWidth) {
      g.globalAlpha = 0.8;
      g.drawImage(this.image, 0, 0, size, size);
      g.globalAlpha = 1;
    }
    g.strokeStyle = '#ffffff14';
    g.lineWidth = 1;
    for (let i = 1; i < 6; i++) {
      g.beginPath(); g.moveTo(i * 24 * s, 0); g.lineTo(i * 24 * s, size); g.stroke();
      g.beginPath(); g.moveTo(0, i * 24 * s); g.lineTo(size, i * 24 * s); g.stroke();
    }
    if (!this.route) return;
    const info = layout(this.route);
    const robot = this.route.robot || { width: 18, length: 18 };
    let drive = 0;
    // Drives: others dim, the selected one bright with its control lines.
    this.route.steps.forEach((step, i) => {
      if (!step.drive) return;
      const color = COLORS[drive++ % COLORS.length];
      const selected = i === this.selected;
      const d = info[i];
      g.globalAlpha = selected ? 1 : 0.55;
      g.strokeStyle = color;
      g.lineWidth = (selected ? 4 : 3) * k;
      g.beginPath();
      d.segments.forEach((segment) => segment.samples.forEach((p, j) => { const [x, y] = this.px(p); if (j === 0 && segment === d.segments[0]) g.moveTo(x, y); else g.lineTo(x, y); }));
      g.stroke();
      // An arrow half way, showing which way it goes.
      const mid = pointAlong(d, 0.5), ahead = pointAlong(d, 0.53);
      const angle = Math.atan2(-(ahead[1] - mid[1]), ahead[0] - mid[0]);
      const [mx, my] = this.px(mid);
      g.fillStyle = color;
      g.beginPath();
      g.moveTo(mx + Math.cos(angle) * 9 * k, my + Math.sin(angle) * 9 * k);
      g.lineTo(mx + Math.cos(angle + 2.5) * 7 * k, my + Math.sin(angle + 2.5) * 7 * k);
      g.lineTo(mx + Math.cos(angle - 2.5) * 7 * k, my + Math.sin(angle - 2.5) * 7 * k);
      g.fill();
      if (selected) {
        g.setLineDash([4 * k, 4 * k]);
        g.lineWidth = 1 * k;
        g.strokeStyle = '#ffffffaa';
        d.segments.forEach((segment) => {
          if (segment.pts.length < 3) return;
          g.beginPath();
          segment.pts.forEach((p, j) => { const [x, y] = this.px(p); if (j) g.lineTo(x, y); else g.moveTo(x, y); });
          g.stroke();
        });
        if (step.drive.heading && step.drive.heading.type === 'face') {
          const [fx, fy] = this.px([step.drive.heading.x, step.drive.heading.y]);
          const [ex, ey] = this.px([d.end.x, d.end.y]);
          g.strokeStyle = '#ff7b72';
          g.beginPath(); g.moveTo(ex, ey); g.lineTo(fx, fy); g.stroke();
        }
        g.setLineDash([]);
        this.drawRobot(g, d.start, robot, '#ffffff55');
        this.drawRobot(g, d.end, robot, color);
      } else {
        this.drawRobot(g, d.end, robot, `${color}55`, true);
      }
      g.globalAlpha = 1;
    });
    // The start pose.
    this.drawRobot(g, { x: this.route.start.x, y: this.route.start.y, h: this.route.start.heading }, robot, '#3fb950');
    // Handles.
    for (const h of this.handles()) {
      const [x, y] = this.px(h.at);
      g.lineWidth = 2 * k;
      if (h.kind === 'via') { g.fillStyle = '#151b23'; g.strokeStyle = '#ffffff'; g.beginPath(); g.arc(x, y, 5 * k, 0, Math.PI * 2); g.fill(); g.stroke(); }
      else if (h.kind === 'to') {
        const last = this.route.steps[h.step].drive.segments.length - 1 === h.segment;
        g.fillStyle = last ? '#f5b301' : '#ffffff'; g.strokeStyle = '#151b23';
        g.beginPath(); g.arc(x, y, (last ? 7 : 6) * k, 0, Math.PI * 2); g.fill(); g.stroke();
      } else if (h.kind === 'event') {
        g.fillStyle = '#f5b301'; g.strokeStyle = '#151b23';
        g.beginPath(); g.moveTo(x, y - 7 * k); g.lineTo(x + 7 * k, y); g.lineTo(x, y + 7 * k); g.lineTo(x - 7 * k, y); g.closePath(); g.fill(); g.stroke();
      } else if (h.kind === 'face') {
        g.strokeStyle = '#ff7b72';
        g.beginPath(); g.arc(x, y, 7 * k, 0, Math.PI * 2); g.moveTo(x - 11 * k, y); g.lineTo(x + 11 * k, y); g.moveTo(x, y - 11 * k); g.lineTo(x, y + 11 * k); g.stroke();
      } else if (h.kind === 'start-heading') {
        g.fillStyle = '#3fb950'; g.beginPath(); g.arc(x, y, 5 * k, 0, Math.PI * 2); g.fill();
      }
    }
    // Adding a drive: where it would go.
    if (this.mode === 'add-drive' && this.hover) {
      const from = this.selected >= 0 ? info[this.selected].end : info.length ? info.at(-1).end : { x: this.route.start.x, y: this.route.start.y };
      const [ax, ay] = this.px([from.x, from.y]);
      const [bx, by] = this.px(this.hover);
      g.setLineDash([6 * k, 5 * k]); g.strokeStyle = '#f5b301'; g.lineWidth = 2 * k;
      g.beginPath(); g.moveTo(ax, ay); g.lineTo(bx, by); g.stroke(); g.setLineDash([]);
    }
    if (this.hover) {
      g.fillStyle = '#e6edf3';
      g.font = `${12 * k}px system-ui`;
      g.fillText(`${this.hover[0]}, ${this.hover[1]}`, 8 * k, size - 8 * k);
    }
  }

  drawRobot(g, pose, robot, color, faint = false) {
    const s = this.canvas.width / FIELD;
    const k = window.devicePixelRatio || 1;
    const [x, y] = this.px([pose.x, pose.y]);
    g.save();
    g.translate(x, y);
    g.rotate((-pose.h * Math.PI) / 180);
    const w = (robot.length || 18) * s, h = (robot.width || 18) * s;
    g.strokeStyle = color;
    g.lineWidth = (faint ? 1 : 2) * k;
    g.strokeRect(-w / 2, -h / 2, w, h);
    g.beginPath(); g.moveTo(0, 0); g.lineTo(w / 2 + 4 * k, 0); g.stroke();
    g.restore();
  }

  // ---- Panel -----------------------------------------------------------------------------------------

  label(name) {
    const def = this.actions.find((a) => a.name === name);
    return def ? def.label : name;
  }

  summary(step, info) {
    if (step.drive) {
      const d = step.drive;
      const heading = d.heading || { type: 'travel' };
      const how = heading.type === 'turn' ? `turn to ${heading.degrees}°` : heading.type === 'face' ? 'face a point'
        : heading.type === 'keep' ? 'keep facing' : heading.reverse ? 'backwards' : 'face travel';
      const parts = [`to ${info.end.x}, ${info.end.y}`, how];
      if (d.segments.length > 1) parts.push(`${d.segments.length - 1} waypoint${d.segments.length > 2 ? 's' : ''}`);
      if (d.speedPercent) parts.push(`${d.speedPercent}%`);
      if (d.events?.length) parts.push(`◆ ${d.events.length}`);
      return `<b>Drive ${escapeHtml(d.name)}</b> <span class="muted">${escapeHtml(parts.join(' · '))}</span>`;
    }
    const def = this.actions.find((a) => a.name === step.action);
    const values = (def?.params || []).map((p) => `${step.params?.[p.name] ?? p.default}${p.unit === '°' ? '°' : ` ${p.unit}`}`);
    return `<b>${escapeHtml(this.label(step.action))}</b> <span class="muted">${escapeHtml(values.join(', '))}</span>`;
  }

  render() {
    this.draw();
    if (!this.route) { this.panel.innerHTML = '<div class="muted">Loading…</div>'; return; }
    const route = this.route;
    const info = layout(route);
    let total = 0;
    const times = route.steps.map((step, i) => {
      const t = step.drive ? driveSeconds(info[i].length, step.drive.speedPercent) : functionSeconds(step);
      total += t;
      return t;
    });
    const fileOptions = this.files.map((f) => `<option value="${escapeHtml(f)}" ${f === this.path ? 'selected' : ''}>${escapeHtml(f.replace(/^autos\/|\.json$/g, ''))}</option>`).join('');
    const stepFunctions = this.actions.map((a) => `<option value="${escapeHtml(a.name)}">${escapeHtml(a.label)}</option>`).join('');
    const dirty = this.dirty();
    this.panel.innerHTML = `
      <div class="auton-files">
        <select id="auton-file" aria-label="Auton file">${this.path ? '' : '<option selected>(new, not saved)</option>'}${fileOptions}</select>
        <button class="btn small" id="auton-new">New</button>
        <button class="btn small primary ${dirty ? 'pulse' : ''}" id="auton-save" data-testid="auton-save">Save</button>
        <button class="btn small" id="auton-save-as">Save as…</button>
        <button class="btn small" id="auton-history" ${this.path ? '' : 'disabled'}>History</button>
      </div>
      <div class="auton-meta">
        <label>Name <input id="auton-name" value="${escapeHtml(route.name || '')}"></label>
        <span class="${total > 30 ? 'bad-text' : 'muted'}" id="auton-time">about ${total.toFixed(1)} s of 30${total > 30 ? ': too long!' : ''}</span>
        <span class="muted">${dirty ? '<b class="warn-text">not saved</b>' : this.path ? 'saved' : ''}</span>
        <button class="btn small" id="auton-undo" ${this.undoStack.length ? '' : 'disabled'} title="Undo (Ctrl+Z)">↶</button>
        <button class="btn small" id="auton-redo" ${this.redoStack.length ? '' : 'disabled'} title="Redo (Ctrl+Shift+Z)">↷</button>
      </div>
      <ol class="auton-steps" id="auton-steps">
        <li class="auton-step start ${this.selected === -1 ? 'selected' : ''}" data-step="-1">
          <span class="num">●</span><span class="what"><b>Start</b>
            <span class="xyz">x <input type="number" step="0.5" data-start="x" value="${route.start.x}">
            y <input type="number" step="0.5" data-start="y" value="${route.start.y}">
            facing <input type="number" step="5" data-start="heading" value="${route.start.heading}">°</span></span>
        </li>
        ${route.steps.map((step, i) => `
        <li class="auton-step ${i === this.selected ? 'selected' : ''} ${step.drive ? 'drive' : 'function'}" data-step="${i}">
          <span class="num">${i + 1}</span><span class="what">${this.summary(step, info[i])}</span>
          <span class="time muted">${times[i] ? `${times[i].toFixed(1)} s` : ''}</span>
          <span class="tools"><button class="icon" data-up="${i}" title="Move up">↑</button><button class="icon" data-down="${i}" title="Move down">↓</button><button class="icon bad" data-remove="${i}" title="Remove">✕</button></span>
        </li>`).join('')}
      </ol>
      <div class="auton-add">
        <button class="btn small ${this.mode === 'add-drive' ? 'primary' : ''}" id="auton-add-drive" data-testid="auton-add-drive">+ Drive</button>
        <select id="auton-function" aria-label="Function">${stepFunctions}</select>
        <button class="btn small" id="auton-add-function">+ Function</button>
        <span class="muted">${this.mode === 'add-drive' ? 'Click the field where the drive should go (Esc cancels).'
          : this.mode === 'pick-face' ? 'Click the point to face (Esc cancels).' : 'Adds after the selected step.'}</span>
      </div>
      <div class="auton-detail" id="auton-detail">${this.detail(info)}</div>
      <div class="muted auton-help">Drag the yellow end points, white waypoints and hollow bend points. Double-click a drive to add a
        waypoint; right-click a waypoint, bend point or ◆ to remove it. Drag the start (green); its dot turns it.
        Snaps to half inches (Shift: free).</div>`;
    this.bindPanel();
  }

  /** The selected step's settings. */
  detail(info) {
    const step = this.route.steps[this.selected];
    if (!step) return '<div class="muted">Select a step to change it. The start: drag the green robot, or type above.</div>';
    if (!step.drive) {
      const def = this.actions.find((a) => a.name === step.action);
      return `<h4>${escapeHtml(this.label(step.action))}</h4><div class="muted">${escapeHtml(def?.description || '')}</div>
        ${(def?.params || []).map((p) => `<label class="row">${escapeHtml(p.label)} <input type="number" step="0.1" min="${p.min}" max="${p.max}"
          data-param="${escapeHtml(p.name)}" value="${step.params?.[p.name] ?? p.default}"> ${escapeHtml(p.unit)}</label>`).join('')}`;
    }
    const d = step.drive;
    const heading = d.heading || { type: 'travel' };
    const kind = heading.type === 'travel' && heading.reverse ? 'backwards' : heading.type;
    const start = info[this.selected].start;
    const pathFunctions = this.actions.filter((a) => a.onPath);
    return `
      <h4>Drive <input id="drive-name" value="${escapeHtml(d.name)}" aria-label="Drive name"></h4>
      <div class="muted">Starts at ${start.x}, ${start.y} facing ${Math.round(wrap(start.h))}° (where the step before ends).</div>
      <label class="row">Heading <select id="drive-heading" data-testid="drive-heading">
        ${HEADINGS.map(([value, text]) => `<option value="${value}" ${value === kind ? 'selected' : ''}>${text}</option>`).join('')}</select>
        ${kind === 'turn' ? `<input type="number" step="5" id="drive-turn" value="${heading.degrees}">° by <input type="number" step="10" min="10" max="100" id="drive-by" value="${Math.round((heading.by ?? 1) * 100)}">% of the drive` : ''}
        ${kind === 'face' ? `x <input type="number" step="0.5" id="drive-face-x" value="${heading.x}"> y <input type="number" step="0.5" id="drive-face-y" value="${heading.y}">
          <button class="btn small" id="drive-face-pick">Pick on field</button>` : ''}
      </label>
      <label class="row">Speed <input type="number" step="5" min="1" max="100" id="drive-speed" placeholder="100" value="${d.speedPercent ?? ''}"> % of top speed</label>
      <div class="sub">Points</div>
      <table class="kv auton-points">
        ${d.segments.map((segment, s) => `<tr>
          <td>${s === d.segments.length - 1 ? 'End' : `Waypoint ${s + 1}`}</td>
          <td>x <input type="number" step="0.5" data-to="${s}" data-axis="0" value="${segment.to[0]}"> y <input type="number" step="0.5" data-to="${s}" data-axis="1" value="${segment.to[1]}"></td>
          <td>${(segment.via || []).length} bend${(segment.via || []).length === 1 ? '' : 's'} <button class="btn small" data-bend="${s}" title="Add a bend point to the part before this point">+ bend</button>
            ${s < d.segments.length - 1 ? `<button class="btn small" data-smooth="${s}" title="Line up the bends on both sides so the robot drives smoothly through">Smooth</button>
            <button class="icon bad" data-unwaypoint="${s}" title="Remove this waypoint">✕</button>` : ''}</td></tr>`).join('')}
      </table>
      <div class="sub">Functions on this drive</div>
      ${(d.events || []).map((event, e) => {
        const def = this.actions.find((a) => a.name === event.action);
        return `<div class="row">◆ <select data-event-action="${e}">${pathFunctions.map((a) => `<option value="${escapeHtml(a.name)}" ${a.name === event.action ? 'selected' : ''}>${escapeHtml(a.label)}</option>`).join('')}</select>
          at <input type="number" step="5" min="0" max="100" data-event-at="${e}" value="${Math.round(event.at * 100)}">%
          ${(def?.params || []).map((p) => `${escapeHtml(p.label)} <input type="number" step="0.1" data-event-param="${e}" data-name="${escapeHtml(p.name)}" value="${event.params?.[p.name] ?? p.default}">`).join('')}
          <button class="icon bad" data-event-remove="${e}" title="Remove">✕</button></div>`;
      }).join('')}
      <div class="row"><select id="event-new">${pathFunctions.map((a) => `<option value="${escapeHtml(a.name)}">${escapeHtml(a.label)}</option>`).join('')}</select>
        <button class="btn small" id="event-add">+ on this drive</button> <span class="muted">(drag the ◆ along the drive)</span></div>`;
  }

  bindPanel() {
    const $ = (id) => document.getElementById(id);
    const on = (selector, type, fn) => this.panel.querySelectorAll(selector).forEach((el) => el.addEventListener(type, (event) => fn(el, event)));
    $('auton-file').addEventListener('change', (event) => this.open(event.target.value));
    $('auton-new').addEventListener('click', () => { if (!this.dirty() || window.confirm('Discard the changes to this auton?')) this.newAuton(); });
    $('auton-save').addEventListener('click', () => this.save(false));
    $('auton-save-as').addEventListener('click', () => this.save(true));
    $('auton-history').addEventListener('click', () => window.dispatchEvent(new CustomEvent('biobuzz:history', { detail: this.path })));
    $('auton-undo').addEventListener('click', () => this.undo());
    $('auton-redo').addEventListener('click', () => this.redo());
    $('auton-name').addEventListener('change', (event) => this.change((r) => { r.name = event.target.value; }));
    $('auton-add-drive').addEventListener('click', () => { this.mode = this.mode === 'add-drive' ? null : 'add-drive'; this.render(); });
    $('auton-add-function').addEventListener('click', () => this.addFunction($('auton-function').value));
    on('[data-start]', 'change', (el) => { const v = Number(el.value); if (Number.isFinite(v)) this.change((r) => { r.start[el.dataset.start] = v; }); });
    on('.auton-step', 'click', (el, event) => {
      if (event.target.closest('button, input')) return;
      this.selected = Number(el.dataset.step);
      this.render();
    });
    on('[data-up]', 'click', (el) => this.move(Number(el.dataset.up), -1));
    on('[data-down]', 'click', (el) => this.move(Number(el.dataset.down), 1));
    on('[data-remove]', 'click', (el) => this.remove(Number(el.dataset.remove)));
    const step = this.route.steps[this.selected];
    if (!step) return;
    if (!step.drive) {
      on('[data-param]', 'change', (el) => { const v = Number(el.value); if (Number.isFinite(v)) this.change(() => { step.params = { ...(step.params || {}), [el.dataset.param]: v }; }); });
      return;
    }
    const d = step.drive;
    $('drive-name').addEventListener('change', (event) => this.change(() => { d.name = event.target.value.trim() || d.name; }));
    $('drive-heading').addEventListener('change', (event) => {
      const value = event.target.value;
      const end = layout(this.route)[this.selected].end;
      this.change(() => {
        if (value === 'travel') d.heading = { type: 'travel' };
        else if (value === 'backwards') d.heading = { type: 'travel', reverse: true };
        else if (value === 'keep') d.heading = { type: 'keep' };
        else if (value === 'turn') d.heading = { type: 'turn', degrees: Math.round(wrap(end.h)) };
        else d.heading = { type: 'face', x: 72, y: 144 };
      });
      if (value === 'face') { this.mode = 'pick-face'; this.render(); }
    });
    const number = (id, fn) => { const el = $(id); if (el) el.addEventListener('change', () => { const v = Number(el.value); if (el.value.trim() === '' || Number.isFinite(v)) this.change(() => fn(el.value.trim() === '' ? null : v)); }); };
    number('drive-turn', (v) => { if (v !== null) d.heading.degrees = v; });
    number('drive-by', (v) => { if (v === null || v >= 100) delete d.heading.by; else d.heading.by = Math.max(0.1, v / 100); });
    number('drive-face-x', (v) => { if (v !== null) d.heading.x = v; });
    number('drive-face-y', (v) => { if (v !== null) d.heading.y = v; });
    number('drive-speed', (v) => { if (v === null || v >= 100) delete d.speedPercent; else d.speedPercent = Math.max(1, Math.round(v)); });
    if ($('drive-face-pick')) $('drive-face-pick').addEventListener('click', () => { this.mode = 'pick-face'; this.render(); });
    on('[data-to]', 'change', (el) => { const v = Number(el.value); if (Number.isFinite(v)) this.change(() => { d.segments[Number(el.dataset.to)].to[Number(el.dataset.axis)] = v; }); });
    on('[data-bend]', 'click', (el) => this.addBend(this.selected, Number(el.dataset.bend)));
    on('[data-smooth]', 'click', (el) => this.smooth(this.selected, Number(el.dataset.smooth)));
    on('[data-unwaypoint]', 'click', (el) => this.removeWaypoint(this.selected, Number(el.dataset.unwaypoint)));
    on('[data-event-action]', 'change', (el) => this.change(() => {
      const event = d.events[Number(el.dataset.eventAction)];
      const def = this.actions.find((a) => a.name === el.value);
      event.action = el.value;
      event.params = Object.fromEntries((def?.params || []).map((p) => [p.name, p.default]));
    }));
    on('[data-event-at]', 'change', (el) => { const v = Number(el.value); if (Number.isFinite(v)) this.change(() => { d.events[Number(el.dataset.eventAt)].at = Math.min(1, Math.max(0, v / 100)); }); });
    on('[data-event-param]', 'change', (el) => { const v = Number(el.value); if (Number.isFinite(v)) this.change(() => { const e = d.events[Number(el.dataset.eventParam)]; e.params = { ...(e.params || {}), [el.dataset.name]: v }; }); });
    on('[data-event-remove]', 'click', (el) => this.change(() => { d.events.splice(Number(el.dataset.eventRemove), 1); if (!d.events.length) delete d.events; }));
    $('event-add').addEventListener('click', () => {
      const def = this.actions.find((a) => a.name === $('event-new').value);
      if (!def) return;
      this.change(() => { d.events = [...(d.events || []), { at: 0.5, action: def.name, params: Object.fromEntries(def.params.map((p) => [p.name, p.default])) }]; });
    });
  }
}
